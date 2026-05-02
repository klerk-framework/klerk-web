package dev.klerkframework.web.assets

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.web.AdminUIPluginIntegration
import dev.klerkframework.web.AdminUI
import dev.klerkframework.web.PluginPage
import dev.klerkframework.web.klerkFormValidationJs
import io.ktor.http.*
import io.ktor.http.HttpHeaders.ContentEncoding
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.io.asSource
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.plus

private const val contentEncodingBrotli = "br"
private val log = KotlinLogging.logger {}

private val builtInAssets = setOf(klerkFormValidationJs)

public class AssetsPlugin<C : KlerkContext, V>(userAssets: Set<KlerkAsset>) : AdminUIPluginIntegration<C, V> {

    private lateinit var textAssets: List<Model<TextAsset>>
    override val name: String = "Assets"
    private val assets = userAssets.plus(builtInAssets)

    override val description: String =
        """Plugin that efficiently serves static assets. 
            |Handles cache-control and cache busting.
            |If the 'brotli' command line tool is installed, it will be used to serve Brotli-compressed text assets.
            |It can work together with the Ktor Compression plugin.""".trimMargin()

    private val textAssetCollections = ModelViews<TextAsset, C>()

    override fun mergeConfig(previous: Config<C, V>): Config<C, V> {
        val managedModels = previous.managedModels.toMutableSet()
        managedModels.add(
            ManagedModel(
                TextAsset::class, createTextResourceStatemachine(), textAssetCollections
            )
        )
        return previous.copy(managedModels = managedModels)
    }

    private lateinit var _klerk: Klerk<C, V>

    override suspend fun start(klerk: Klerk<C, V>) {
        _klerk = klerk
        var context = klerk.config.systemContextProvider(SystemIdentity)
        textAssets = klerk.read(context) {
            list(textAssetCollections.all)
        }

        val brotliAvailable = isBrotliAvailable()

        assets.forEach { asset ->
            val resourceContent = ResourceReader.readResource(asset.resourcePath)
                ?: throw IllegalStateException("Resource not found: ${asset.resourcePath}")

            val contentType = when (asset) {
                is CssAsset -> AssetContentType("text/css")
                is JsAsset -> AssetContentType("application/javascript")
                else -> throw IllegalArgumentException("Unsupported asset type: ${asset::class.simpleName}")
            }

            Base64hash.from(resourceContent).let { base64hash ->
                asset.setHash(base64hash)
                // TODO: move compression to a job
                // at startup: should delete any existing future job

                if (textAssets.none { ta -> ta.props.hash == base64hash }) {
                    val brotliId = if (brotliAvailable) {
                        val brotli = compressBrotli(resourceContent.byteInputStream())
                        val brotliToken = klerk.keyValueStore.prepareBlob(brotli.inputStream())
                        klerk.keyValueStore.put(brotliToken)
                    } else null

                    context = klerk.config.systemContextProvider(SystemIdentity)

                    klerk.handle(
                        Command(
                            event = CreateTextAsset,
                            model = null,
                            params = CreateTextAssetParams(
                                AssetPath(asset.resourcePath),
                                contentType,
                                base64hash,
                                brotliId,
                            )
                        ),
                        context,
                        ProcessingOptions(CommandToken.simple())
                    )
                }
            }
        }
        deleteObsoleteTextAssets(assets, textAssets)

        textAssets = klerk.read(context) {
            list(textAssetCollections.all)
        }
    }

    private suspend fun deleteObsoleteTextAssets(assets: Set<KlerkAsset>, textAssets: List<Model<TextAsset>>) {
        textAssets
            .filter { ta -> assets.none { a -> a._hash == ta.props.hash } }
            .forEach {
                _klerk.handle(
                    Command(
                        event = DeleteTextAsset,
                        model = it.id,
                        params = null
                    ),
                    context = _klerk.config.systemContextProvider(SystemIdentity),
                    ProcessingOptions(
                        CommandToken.simple()
                    )
                )
            }
    }

    private fun isBrotliAvailable(): Boolean {
        try {
            val process = ProcessBuilder("brotli", "-V")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exitCode = process.waitFor()
            return exitCode == 0
        } catch (e: Exception) {
            log.warn { "Brotli is not available" }
            return false
        }
    }

    private fun compressBrotli(input: ByteArrayInputStream): ByteArray {
        val process = ProcessBuilder("brotli", "-Z", "--stdout")  // Compress to stdout
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        // Thread to write to brotli's stdin
        val writerThread = Thread {
            input.copyTo(process.outputStream)
            process.outputStream.close()
        }
        writerThread.start()

        // Read compressed output from brotli's stdout
        val compressedOutput = ByteArrayOutputStream()
        process.inputStream.copyTo(compressedOutput)

        // Wait for writing thread and brotli process to finish
        writerThread.join()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("brotli process failed with exit code $exitCode")
        }
        return compressedOutput.toByteArray()
    }

    override val page: PluginPage<C, V> = Page(textAssetCollections)

    override fun registerExtraRoutes(routing: Routing, basePath: String) {
        routing.get("$basePath/_assets/{key...}") {
            val path = call.parameters.getAll("key")?.joinToString("/")
            if (path == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val asset = assets.firstOrNull { a -> a.getPathAndHash() == "/${path}" }
            if (asset == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val contentType = when (asset) {
                is CssAsset -> ContentType.Text.CSS
                is JsAsset -> ContentType.Application.JavaScript
                else -> throw IllegalArgumentException("Unsupported asset type: ${asset::class.simpleName}")
            }
            val textAsset = textAssets.firstOrNull { ta -> ta.props.path.value == asset.resourcePath }
            if (textAsset == null) {
                serveUncompressed(asset, contentType)
                return@get
            }

            if (call.request.headers[HttpHeaders.AcceptEncoding]?.contains(contentEncodingBrotli) ?: false &&
                textAsset.props.brotli != null
            ) {
                serveBrotli(call, textAsset.props.brotli!!, contentType)
                return@get
            }
            serveUncompressed(asset, contentType)
        }
    }

    /**
     * Serve uncompressed (but if the Ktor Compression plugin is enabled, it may still be compressed)
     */
    private suspend fun RoutingContext.serveUncompressed(
        asset: KlerkAsset,
        contentType: ContentType
    ) {
        val inputString = this::class.java.getResourceAsStream(asset.resourcePath)
        if (inputString != null) {
            setCacheControl(call)
            call.respondSource(inputString.asSource(), contentType, HttpStatusCode.OK)
            return
        }
        call.respond(HttpStatusCode.InternalServerError)
    }

    private suspend fun serveBrotli(call: RoutingCall, id: BinaryKeyValueID, contentType: ContentType) {
        call.response.headers.append(ContentEncoding, contentEncodingBrotli)
        val ctx = _klerk.config.systemContextProvider(SystemIdentity)
        val inputStream = _klerk.keyValueStore.get(id, ctx)
        setCacheControl(call)
        call.suppressCompression()
        call.respondSource(inputStream.asSource(), contentType, HttpStatusCode.OK)
        inputStream.close()
    }

    private fun setCacheControl(call: RoutingCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "max-age=31536000, public, immutable")
    }

}

public class Page<C : KlerkContext, V>(private val textAssetCollections: ModelViews<TextAsset, C>) :
    PluginPage<C, V> {
    override val buttonText: String = "Assets"

    override suspend fun render(
        call: ApplicationCall,
        config: AdminUI<C, V>,
        klerk: Klerk<C, V>
    ) {
        val context = config.contextProvider(call, klerk)
        val textAssets = klerk.read(context) {
            list(textAssetCollections.all)
        }

        data class AssetDetails(val path: String, val original: Int, val brotli: Int?)

        val sizes = textAssets.map {
            AssetDetails(
                it.props.path.value,
                ResourceReader.readResource(it.props.path.value)?.length ?: 0,
                it.props.brotli?.let { klerk.keyValueStore.get(it, context).readBytes().size })
        }

        call.respondHtml {
            head {
                styleLink(config.cssPath)
            }
            body {
                h1 { +"Assets" }
                table {
                    tr {
                        th { +"Resource Path" }
                        th { +"Original size" }
                        th { +"Brotli size" }
                    }
                    sizes.forEach { s ->
                        tr {
                            td { +s.path }
                            td { +s.original.toString() }
                            td { +(s.brotli ?: "-").toString() }
                        }
                    }
                }
            }
        }
    }
}


public abstract class KlerkAsset(public val resourcePath: String) {
    internal var _hash: Base64hash? = null

    init {
        require(resourcePath.startsWith("/")) { "Resource path must start with '/'" }
    }

    internal fun setHash(hash: Base64hash) {
        _hash = hash
    }

    internal fun getUrl(): String {
        return "/admin/_assets${getPathAndHash()}"
    }

    internal fun getPathAndHash(): String {
        if (_hash == null) {
            throw IllegalStateException("Asset '$resourcePath' has not been registered")
        }
        return "${resourcePath}_${_hash!!.value}"
    }
}

public class CssAsset(resourcePath: String) : KlerkAsset(resourcePath)

public class JsAsset(resourcePath: String) : KlerkAsset(resourcePath) // TODO: Subresource Integrity? nonce?


private object ResourceReader {
    fun readResource(path: String): String? =
        this::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}

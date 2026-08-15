package dev.klerkframework.web.assets

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.web.AdminUIPluginIntegration
import dev.klerkframework.web.WebSupport

import dev.klerkframework.web.AdminUI
import dev.klerkframework.web.PathProvider
import dev.klerkframework.web.PluginPage
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

private const val contentEncodingBrotli = "br"
private val log = KotlinLogging.logger {}

/**
 * Serves static assets with cache-busting URLs, long cache headers and Brotli compression when available. See
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/assets.md).
 */
public class AssetsPlugin<C : KlerkContext, V>(private val userAssetResources: Set<KlerkAsset>) : AdminUIPluginIntegration<C, V> {

    private lateinit var assets: Set<KlerkAsset>
    private lateinit var textAssets: List<Model<TextAsset>>
    override val name: String = "Assets"

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
        var context = _klerk.config.systemContextProvider(SystemIdentity)
        textAssets = _klerk.read(context) {
            list(textAssetCollections.all)
        }

        val brotliAvailable = isBrotliAvailable()

        assets = userAssetResources.plus(formJs).plus(uploadJs)
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
                    context = _klerk.config.systemContextProvider(SystemIdentity)

                    val brotliId = if (brotliAvailable) {
                        val brotli = compressBrotli(resourceContent.byteInputStream())
                        _klerk.attachedData.prepare(brotli.inputStream(), context)
                    } else null

                    _klerk.handle(
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

        textAssets = _klerk.read(context) {
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
        log.info { "Compressing text asset with brotli" }
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
        log.info { "Compressed text asset with brotli" }
        return compressedOutput.toByteArray()
    }

    override val page: PluginPage<C, V> = Page(textAssetCollections)

    override fun registerExtraRoutes(route: Route, pathProvider: PathProvider) {
        log.info { "Registering assets route: ${pathProvider.assetsBase}/{key...}" }
        route.get("${pathProvider.assetsBase}/{key...}") {
            val path = call.parameters.getAll("key")?.joinToString("/")
            if (path == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val asset = assets.firstOrNull { a -> a.getPathAndHash() == path }
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
        val resourcePath = if (asset.resourcePath.startsWith("/")) asset.resourcePath else "/${asset.resourcePath}"
        val inputString = this::class.java.getResourceAsStream("/assets$resourcePath")
        if (inputString != null) {
            setCacheControl(call)
            call.respondSource(inputString.asSource(), contentType, HttpStatusCode.OK)
            return
        }
        log.warn { "Could not find resource: $resourcePath" }
        call.respond(HttpStatusCode.NotFound)
    }

    private suspend fun serveBrotli(call: RoutingCall, id: AttachedBlobID, contentType: ContentType) {
        call.response.headers.append(ContentEncoding, contentEncodingBrotli)
        val ctx = _klerk.config.systemContextProvider(SystemIdentity)
        val inputStream = _klerk.attachedData.get(id, ctx)
        setCacheControl(call)
        call.suppressCompression()
        call.respondSource(inputStream.asSource(), contentType, HttpStatusCode.OK)
        inputStream.close()
    }

    private fun setCacheControl(call: RoutingCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "max-age=31536000, public, immutable")
    }

}

/** Lists the served assets and their sizes in the Admin UI. */
public class Page<C : KlerkContext, V>(private val textAssetCollections: ModelViews<TextAsset, C>) :
    PluginPage<C, V> {
    override val buttonText: String = "Assets"

    override suspend fun respond(
        call: ApplicationCall,
        support: WebSupport<C, V>,
        klerk: Klerk<C, V>
    ) {
        val context = support.contextProvider(call, klerk)
        val textAssets = klerk.read(context) {
            list(textAssetCollections.all)
        }

        val sizes = textAssets.map { asset ->
            AssetDetails(
                asset.props.path.value,
                ResourceReader.readResource(asset.props.path.value)?.length ?: 0,
                asset.props.brotli?.let { blobId -> klerk.attachedData.get(blobId, context).readBytes().size })
        }

        support.respondPage(call, "Assets") {
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

    private data class AssetDetails(val path: String, val original: Int, val brotli: Int?)
}


/** A file under `src/main/resources/assets`, addressed by its path relative to that directory. */
public abstract class KlerkAsset(public val resourcePath: String) {
    internal var _hash: Base64hash? = null

    internal fun setHash(hash: Base64hash) {
        _hash = hash
    }

    internal fun getPathAndHash(): String {
        if (_hash == null) {
            throw IllegalStateException("Asset '$resourcePath' has not been registered")
        }
        return "${resourcePath}_${_hash!!.value}"
    }
}

/** A stylesheet. Give it to [dev.klerkframework.web.Layout] to have the `<link>` rendered for you. */
public class CssAsset(resourcePath: String) : KlerkAsset(resourcePath)

/** A script. Build its URL with [dev.klerkframework.web.PathProvider.assetPath]. */
public class JsAsset(resourcePath: String) : KlerkAsset(resourcePath) // TODO: Subresource Integrity? nonce?


private object ResourceReader {
    fun readResource(path: String): String? {
        val resourcePath = if (path.startsWith("/")) path else "/$path"
        return this::class.java.getResourceAsStream("/assets$resourcePath")?.bufferedReader()?.use { it.readText() }
    }

}



//internal val klerkFormValidationJs = JsAsset("/assets/klerkFormValidation.js", pathProvider)

internal val klerkFormValidationJsFile = "klerkFormValidation.js"

internal val formJs = JsAsset(klerkFormValidationJsFile)

internal val klerkUploadJsFile = "klerkUpload.js"

/** The uploader used by `FormTemplate.file()`. Always served, like the form validation script. */
public val uploadJs: JsAsset = JsAsset(klerkUploadJsFile)

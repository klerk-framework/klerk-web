package dev.klerkframework.web.assets

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.collection.asSequence
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.web.AdminUIPluginIntegration
import dev.klerkframework.web.WebSupport

import dev.klerkframework.web.AdminUI
import dev.klerkframework.web.PathProvider
import dev.klerkframework.web.PluginPage
import dev.klerkframework.web.image.ASSET
import dev.klerkframework.web.image.ImagePlugin
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
import java.nio.file.Files

private const val contentEncodingBrotli = "br"
private val log = KotlinLogging.logger {}

/**
 * Serves static assets with cache-busting URLs, long cache headers and Brotli compression when available. See
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/assets.md).
 */
public class AssetsPlugin<C : KlerkContext, V>(
    private val userAssetResources: Set<KlerkAsset>,
    private val images: ImagePlugin<C, V>? = null,
) : AdminUIPluginIntegration<C, V> {

    private lateinit var assets: Set<KlerkAsset>
    private lateinit var textAssets: List<Model<TextAsset>>
    override val name: String = "Assets"

    override val description: String =
        """Plugin that efficiently serves static assets. 
            |Handles cache-control and cache busting.
            |If the 'brotli' command line tool is installed, it will be used to serve Brotli-compressed text assets.
            |It can work together with the Ktor Compression plugin.""".trimMargin()

    private val textAssetCollections = ModelViews<TextAsset, C>()

    override fun mergeSpecification(previous: Specification<C, V>): Specification<C, V> {
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
        var context = _klerk.spec.systemContextProvider(SystemIdentity)
        textAssets = _klerk.read(context) {
            textAssetCollections.all.asSequence().toList()
        }

        val brotliAvailable = isBrotliAvailable()

        assets = userAssetResources.plus(formJs).plus(uploadJs)
        assets.filterIsInstance<ImageAsset>().forEach { prepareImageAsset(it) }
        assets.filter { it !is ImageAsset }.forEach { asset ->
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
                    context = _klerk.spec.systemContextProvider(SystemIdentity)

                    val brotliId = if (brotliAvailable) {
                        val brotli = compressBrotli(resourceContent.byteInputStream())
                        _klerk.attachedData.prepare(brotli.inputStream(), CompressedAsset::class, context)
                    } else null

                    _klerk.handle(
                        Command(
                            event = CreateTextAsset,
                            model = null,
                            params = CreateTextAssetParams(
                                AssetPath(asset.resourcePath),
                                contentType,
                                base64hash,
                                brotliId?.let { CompressedAsset(it) },
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
            textAssetCollections.all.asSequence().toList()
        }
    }

    /**
     * Hashes an image asset over its real bytes, extracts it where the processor can read it, and has the image
     * plugin measure it.
     *
     * Nothing is compressed: JPEG, PNG, WebP and AVIF already are, and Brotli on top of them is work for nothing.
     */
    private suspend fun prepareImageAsset(asset: ImageAsset) {
        val bytes = ResourceReader.readBytes(asset.resourcePath)
            ?: throw IllegalStateException("Resource not found: ${asset.resourcePath}")
        asset.setHash(Base64hash.from(bytes))
        val plugin = checkNotNull(images) {
            "The image asset '${asset.resourcePath}' needs an ImagePlugin: AssetsPlugin(assets, images = images)"
        }
        val file = Files.createTempFile("klerk-asset-", "-${asset.hash()}")
        file.toFile().deleteOnExit()
        Files.write(file, bytes)
        plugin.registerAsset(asset.hash(), file, asset.resourcePath)
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
                    context = _klerk.spec.systemContextProvider(SystemIdentity),
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
                serveImageVariant(path)
                return@get
            }
            if (asset is ImageAsset) {
                // No variant was asked for, so this is the asset itself. It is public and unchanging, and the hash
                // in the URL is what makes caching it forever safe.
                serveUncompressed(asset, ContentType.parse(asset.contentType))
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
                serveBrotli(call, textAsset.props.brotli!!.id, contentType)
                return@get
            }
            serveUncompressed(asset, contentType)
        }
    }

    /**
     * Serves one variant of an image asset: `<path>_<hash>/<template>-<width>.<format>`.
     *
     * The same machinery as an uploaded image — the same allow-list, the same store, the same job, the same wait —
     * with no authorization, because an asset ships with the application.
     */
    private suspend fun RoutingContext.serveImageVariant(path: String) {
        val plugin = images
        val slash = path.lastIndexOf('/')
        val asset = if (slash <= 0 || plugin == null) null else {
            val head = path.substring(0, slash)
            assets.filterIsInstance<ImageAsset>().firstOrNull { it.getPathAndHash() == head }
        }
        if (asset == null || plugin == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val variant = plugin.parseVariant(path.substring(slash + 1))
        if (variant == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val hash = asset.hash()
        if (plugin.refused(ASSET, hash, variant)) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val generated = plugin.existing(ASSET, hash, variant) ?: run {
            plugin.requestVariant(ASSET, hash, variant)
            plugin.awaitVariant(ASSET, hash, variant)
        }
        if (generated == null) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (plugin.refused(ASSET, hash, variant)) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                plugin.recordUnavailable()
                call.response.headers.append(HttpHeaders.RetryAfter, "1")
                call.respond(HttpStatusCode.ServiceUnavailable)
            }
            return
        }
        setCacheControl(call)
        call.respondFile(generated.toFile())
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
        val ctx = _klerk.spec.systemContextProvider(SystemIdentity)
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
            textAssetCollections.all.asSequence().toList()
        }

        val sizes = textAssets.map { asset ->
            AssetDetails(
                asset.props.path.value,
                ResourceReader.readResource(asset.props.path.value)?.length ?: 0,
                asset.props.brotli?.let { blob -> klerk.attachedData.get(blob.id, context).readBytes().size })
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

    /**
     * The URL path of this asset, content hash included, for [dev.klerkframework.web.PathProvider.assetPath].
     *
     * @throws IllegalStateException if the asset was not given to an [AssetsPlugin].
     */
    public fun getPathAndHash(): String {
        if (_hash == null) {
            throw IllegalStateException("Asset '$resourcePath' has not been registered")
        }
        return "${resourcePath}_${_hash!!.value}"
    }

    /** The content hash on its own, which is what identifies an image asset's variants. */
    internal fun hash(): String = checkNotNull(_hash) { "Asset '$resourcePath' has not been registered" }.value
}

/** A stylesheet. Give it to [dev.klerkframework.web.Layout] to have the `<link>` rendered for you. */
public class CssAsset(resourcePath: String) : KlerkAsset(resourcePath)

/** A script. Build its URL with [dev.klerkframework.web.PathProvider.assetPath]. */
public class JsAsset(resourcePath: String) : KlerkAsset(resourcePath) // TODO: Subresource Integrity? nonce?

/**
 * A picture that ships with the application: a logo, an illustration, a splash image.
 *
 * Give the [AssetsPlugin] an [dev.klerkframework.web.image.ImagePlugin] and it is rendered through the same
 * [dev.klerkframework.web.image.ImageTemplate] as an uploaded image, in the sizes that template declares — so a
 * 4000 px photograph in the jar is not what a phone downloads. Without one, it is served as it is, with the content
 * hash in the URL and cached forever.
 *
 * ```kotlin
 * val splash = ImageAsset("splash.jpg")
 * AssetsPlugin(setOf(css, splash), images = images)
 *
 * image(hero, splash, alt = "")
 * ```
 *
 * Unlike attached data an asset is public: no authorization is evaluated for it, because it is part of the
 * application rather than something one of its users owns. It is measured when the application starts, so a page
 * can reserve space for it from the very first render.
 */
public class ImageAsset(resourcePath: String) : KlerkAsset(resourcePath) {

    /** What this is, from the file's extension. */
    internal val contentType: String = when (resourcePath.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> throw IllegalArgumentException("'$resourcePath' is not an image klerk-web recognises")
    }
}


private object ResourceReader {
    fun readResource(path: String): String? {
        val resourcePath = if (path.startsWith("/")) path else "/$path"
        return this::class.java.getResourceAsStream("/assets$resourcePath")?.bufferedReader()?.use { it.readText() }
    }

    /** As [readResource], without decoding it as text — an image is not text and must not be read as any. */
    fun readBytes(path: String): ByteArray? {
        val resourcePath = if (path.startsWith("/")) path else "/$path"
        return this::class.java.getResourceAsStream("/assets$resourcePath")?.use { it.readBytes() }
    }
}



//internal val klerkFormValidationJs = JsAsset("/assets/klerkFormValidation.js", pathProvider)

internal val klerkFormValidationJsFile = "klerkFormValidation.js"

internal val formJs = JsAsset(klerkFormValidationJsFile)

internal val klerkUploadJsFile = "klerkUpload.js"

/** The uploader used by `FormTemplate.file()`. Always served, like the form validation script. */
public val uploadJs: JsAsset = JsAsset(klerkUploadJsFile)

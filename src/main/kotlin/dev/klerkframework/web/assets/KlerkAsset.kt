package dev.klerkframework.web.assets

import dev.klerkframework.klerk.*
import dev.klerkframework.web.PathProvider
import dev.klerkframework.web.image.ImagePlugin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*

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

internal val klerkFormValidationJsFile = "klerkFormValidation.js"

internal val formJs = JsAsset(klerkFormValidationJsFile)

internal val klerkUploadJsFile = "klerkUpload.js"

/** The uploader used by `FormTemplate.file()`. Always served, like the form validation script. */
public val uploadJs: JsAsset = JsAsset(klerkUploadJsFile)

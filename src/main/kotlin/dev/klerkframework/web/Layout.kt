package dev.klerkframework.web

import dev.klerkframework.web.assets.CssAsset
import kotlinx.html.*

/**
 * Produces whole HTML documents: the `<head>`, the language and the stylesheet.
 *
 * This is the building block that owns page structure. [PathProvider] only builds URLs; it does not know what a page
 * looks like.
 *
 * @param css a stylesheet served by the assets plugin (see [dev.klerkframework.web.assets.AssetsPlugin]).
 * @param externalCssPath a stylesheet served elsewhere, e.g. "https://example.com/classless.css". Mutually
 * exclusive with [css].
 * @param assetsBase where assets are served from. Must match the [PathProvider] used for the same pages.
 * @param lang the value of the `lang` attribute.
 * @param extraHead rendered last in `<head>`, for your own meta tags, scripts or icons.
 */
public open class Layout(
    public val css: CssAsset? = null,
    public val externalCssPath: String? = null,
    public val assetsBase: String = "/_assets",
    public val lang: String = "en",
    private val extraHead: (HEAD.() -> Unit)? = null,
) {

    init {
        require(externalCssPath == null || externalCssPath.startsWith("https://")) {
            "externalCssPath must start with https://"
        }
        require(!(css != null && externalCssPath != null)) { "Cannot specify both css and externalCssPath" }
    }

    /** The URL of the stylesheet, or null if there is none. */
    public fun cssUrl(): String? = externalCssPath ?: css?.let { "$assetsBase/${it.getPathAndHash()}" }

    /**
     * A complete document.
     *
     * @param pageHead rendered after [extraHead], for what only this page needs.
     */
    public open fun page(
        title: String,
        pageHead: (HEAD.() -> Unit)? = null,
        body: BODY.() -> Unit,
    ): HTML.() -> Unit = {
        lang = this@Layout.lang
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +title }
            cssUrl()?.let { styleLink(it) }
            extraHead?.invoke(this)
            pageHead?.invoke(this)
        }
        body { apply(body) }
    }
}

package dev.klerkframework.web.image

import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.web.PathProvider
import dev.klerkframework.web.assets.ImageAsset
import dev.klerkframework.web.WebSupport
import kotlinx.html.FlowContent
import kotlinx.html.PICTURE
import kotlinx.html.img
import kotlinx.html.picture
import kotlinx.html.source
import kotlin.math.roundToInt

/**
 * What one image comes to once the sidecar has been looked up: how to address one of its variants, and its measured
 * size. Where the bytes live is settled here and nowhere else, which is what lets an uploaded image and one that
 * ships with the application go through the same markup.
 */
private class ResolvedImage(
    val url: (segment: String) -> String,
    val sidecar: ImageSidecar?,
)

/** Whether the browser may defer loading the image until it is close to the viewport. */
public enum class ImageLoading { Lazy, Eager }

/**
 * Renders the image [metadata] describes, in the role [template] describes.
 *
 * The metadata is the same one any attached-data URL needs, read inside a read block so that the model and its image
 * come from one snapshot:
 *
 * ```kotlin
 * val (flower, cover) = klerk.read(context) {
 *     val flower = get(flowerID)
 *     flower to attachedData.metadata(flower.props.image.id)
 * }
 *
 * support.respondPage(call, "A flower") {
 *     image(hero, cover, alt = "A flower")
 * }
 * ```
 *
 * `WebSupport` has to be in context, which it is inside
 * [respondPage][dev.klerkframework.web.WebSupport.respondPage]; elsewhere, `with(support) { … }`.
 *
 * The `srcset` is the rendition's [widths][ImageRendition.widths], narrowed to what the image can actually fill — a
 * descriptor never promises more pixels than the file has, and a crop leaves fewer. `width` and `height` are
 * rendered whenever the image has been [measured][ImagePlugin.prepareImageKeepExif] or generated at least once, and are
 * what keeps the page from jumping about while it loads. They are the image's real proportions, so the stylesheet
 * must have `img { max-width: 100%; height: auto }` or every image is rendered at its widest variant.
 *
 * A `<picture>` is rendered when there is anything for the browser to choose between: an alternative per
 * [media condition][ImageTemplate.Builder.on] and a `<source>` per format, so the server never has to vary on
 * `Accept`. Each `<source>` carries its own `width` and `height`, since a differently cropped alternative reserves a
 * differently shaped box. With one format and no alternatives, it is a plain `<img>`.
 *
 * Renders nothing when the value is not an image this plugin scales, or when the template has no width it can be
 * served at.
 *
 * The arguments override the template for this one image, which should be rare — a slot that keeps needing a
 * different `sizes` is a second template. Overriding [loading] to eager on a template that asks for `sizes=auto`
 * renders its fallback instead, since a browser only measures a lazily loaded image.
 */
context(support: WebSupport<C, V>)
public fun <C : KlerkContext, V> FlowContent.image(
    template: ImageTemplate<C, V>,
    metadata: AttachedDataMetadata,
    alt: String,
    sizes: String? = null,
    loading: ImageLoading? = null,
    fetchPriority: FetchPriority? = null,
    classes: String? = null,
) {
    if (!template.images.handles(metadata.contentType)) {
        return
    }
    render(
        template,
        ResolvedImage(
            { segment -> support.pathProvider.attachedDataPath(metadata.id, metadata.hash, segment) },
            template.images.sidecar(metadata.id, metadata.hash),
        ),
        alt,
        sizes,
        loading,
        fetchPriority,
        classes,
    )
}

/** The markup itself, which is the same wherever the bytes came from. */
context(support: WebSupport<C, V>)
private fun <C : KlerkContext, V> FlowContent.render(
    template: ImageTemplate<C, V>,
    reference: ResolvedImage,
    alt: String,
    sizes: String?,
    loading: ImageLoading?,
    fetchPriority: FetchPriority?,
    classes: String?,
) {
    val effectiveLoading = loading ?: template.loading
    val effectivePriority = fetchPriority ?: template.fetchPriority
    val formats =
        template.formats.sortedBy { preference.indexOf(it).takeIf { i -> i >= 0 } ?: preference.size }

    val fallback = settingsFor(template.default, reference, sizes, effectiveLoading) ?: return
    val alternatives = template.alternatives.mapNotNull { rendition ->
        // A rendition of its own `sizes`: the override is for the image, and only the default is what it names.
        settingsFor(rendition, reference, null, effectiveLoading)
    }

    if (alternatives.isEmpty() && formats.size == 1) {
        renderImg(fallback, formats.single(), alt, effectiveLoading, effectivePriority, classes)
        return
    }
    picture {
        // The browser takes the first source whose media and type it likes, so an alternative must offer every
        // format: unlike the default, it cannot fall through to the <img>.
        alternatives.forEach { alternative ->
            formats.forEach { format -> renderSource(alternative, format) }
        }
        formats.dropLast(1).forEach { format -> renderSource(fallback, format) }
        renderImg(fallback, formats.last(), alt, effectiveLoading, effectivePriority, classes)
    }
}

private val preference = listOf("avif", "webp", "jpeg", "png")

private fun PICTURE.renderSource(settings: Settings, format: String) {
    source {
        settings.rendition.media?.let { attributes["media"] = it }
        attributes["type"] = "image/$format"
        attributes["srcset"] = settings.srcset(format)
        attributes["sizes"] = settings.sizes
        settings.height()?.let { height ->
            attributes["width"] = settings.servedWidth(settings.widest).toString()
            attributes["height"] = height.toString()
        }
    }
}

private fun FlowContent.renderImg(
    settings: Settings,
    format: String,
    alt: String,
    loading: ImageLoading,
    fetchPriority: FetchPriority?,
    classes: String?,
) {
    img(alt = alt, classes = classes) {
        src = settings.url(settings.widest, format)
        attributes["srcset"] = settings.srcset(format)
        attributes["sizes"] = settings.sizes
        attributes["loading"] = if (loading == ImageLoading.Lazy) "lazy" else "eager"
        attributes["decoding"] = "async"
        fetchPriority?.let { attributes["fetchpriority"] = it.name.lowercase() }
        settings.height()?.let { height ->
            attributes["width"] = settings.servedWidth(settings.widest).toString()
            attributes["height"] = height.toString()
        }
    }
}

/**
 * What one rendition of one image comes to, or null when there is no width it can be served at.
 */
private fun settingsFor(
    rendition: ImageRendition,
    reference: ResolvedImage,
    sizes: String?,
    loading: ImageLoading,
): Settings? {
    val widths = renderableWidths(rendition, reference)
    if (widths.isEmpty()) {
        return null
    }
    val asked = sizes ?: rendition.sizes
    // A browser ignores `auto` unless the image is lazily loaded, so an eager one is rendered with the fallback.
    val effective = if (loading == ImageLoading.Eager && usesAutoSizes(asked)) withoutAutoSizes(asked) else asked
    return Settings(rendition, reference, widths, effective)
}

/** What one call has decided, so that the parts above do not each take nine arguments. */
private class Settings(
    val rendition: ImageRendition,
    val reference: ResolvedImage,
    val widths: List<Int>,
    val sizes: String,
) {
    val widest: Int get() = widths.last()

    fun url(width: Int, format: String): String = reference.url(rendition.segment(width, format))

    /** Descriptors are the image's real widths, so an image smaller than the ladder is not advertised as bigger. */
    fun srcset(format: String): String =
        widths.joinToString(", ") { "${url(it, format)} ${servedWidth(it)}w" }

    fun servedWidth(width: Int): Int = usableWidth(rendition, reference)?.let { minOf(width, it) } ?: width

    /**
     * The height [widest] is rendered at: the crop's shape, or the image's own.
     *
     * A crop is a ratio the template declared, so it is known whether or not anything has measured the image. Only an
     * uncropped rendition has to wait to be measured, and renders without a height until then.
     */
    fun height(): Int? {
        val width = servedWidth(widest)
        rendition.crop?.let { return it.heightFor(width) }
        val sidecar = reference.sidecar ?: return null
        return maxOf(1, (sidecar.height.toDouble() * width / sidecar.width).roundToInt())
    }
}

/**
 * The widths that can actually be served: the rendition's ladder, and no wider than the image can fill once cut to
 * shape. The narrowest is always kept, so an image smaller than every width still renders.
 */
private fun renderableWidths(rendition: ImageRendition, reference: ResolvedImage): List<Int> {
    val usable = usableWidth(rendition, reference) ?: return rendition.ladder
    return rendition.ladder.filter { it <= usable }.ifEmpty { listOf(rendition.ladder.first()) }
}

/** How wide this image can be served in this rendition, or null while nobody has measured it. */
private fun usableWidth(rendition: ImageRendition, reference: ResolvedImage): Int? {
    val sidecar = reference.sidecar ?: return null
    val info = ImageInfo(sidecar.width, sidecar.height)
    return rendition.crop?.widthOf(info) ?: info.width
}

/**
 * Renders a static image — one that ships with the application rather than one somebody uploaded — in the role
 * [template] describes.
 *
 * ```kotlin
 * val splash = ImageAsset("splash.jpg")
 * AssetsPlugin(setOf(css, splash), images = images)
 *
 * support.respondPage(call, "Welcome") {
 *     image(hero, splash, alt = "")
 * }
 * ```
 *
 * Everything works as it does for an uploaded image, with two differences that follow from an asset being part of
 * the application: it is public, so no authorization is evaluated for it, and it was measured when the application
 * started, so `width` and `height` are always rendered and the page never moves as it loads.
 */
context(support: WebSupport<C, V>)
public fun <C : KlerkContext, V> FlowContent.image(
    template: ImageTemplate<C, V>,
    asset: ImageAsset,
    alt: String,
    sizes: String? = null,
    loading: ImageLoading? = null,
    fetchPriority: FetchPriority? = null,
    classes: String? = null,
) {
    val base = support.pathProvider.assetPath(asset.getPathAndHash())
    render(
        template,
        ResolvedImage({ segment -> "$base/$segment" }, template.images.sidecar(ASSET, asset.hash())),
        alt,
        sizes,
        loading,
        fetchPriority,
        classes,
    )
}
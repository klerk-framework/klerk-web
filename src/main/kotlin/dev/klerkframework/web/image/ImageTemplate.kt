package dev.klerkframework.web.image

import dev.klerkframework.klerk.KlerkContext

/** What the browser should be told about how urgently an image is wanted, i.e. the `fetchpriority` attribute. */
public enum class FetchPriority { High, Low, Auto }

/** Which part of an image a [Crop] keeps. `North` is the one that matters: faces live near the top. */
public enum class Gravity {
    Center, North, South, East, West;

    /** One letter, so that a crop fits in a filename and a job cursor. */
    internal val letter: Char get() = name.first().lowercaseChar()
}

/**
 * The shape an image is cut to: a ratio and where the surviving rectangle sits. `Crop(16, 9)` on a portrait
 * photograph keeps a wide strip of it; nothing is ever enlarged to fill the shape.
 *
 * The ratio, not a size — the width comes from the ladder.
 */
public data class Crop(val width: Int, val height: Int, val gravity: Gravity = Gravity.Center) {

    init {
        require(width > 0 && height > 0) { "A crop is a ratio, so both sides must be positive" }
    }

    /** How wide [source] can be once cut to this shape, in its own pixels. */
    internal fun widthOf(source: ImageInfo): Int =
        maxOf(1, minOf(source.width.toLong(), source.height.toLong() * width / height).toInt())

    /** The height that goes with [width] pixels of this shape. */
    internal fun heightFor(width: Int): Int =
        maxOf(1, Math.round(width.toDouble() * this.height / this.width).toInt())

    /** `16x9c`, for a filename and a job cursor. Parsed by [parseCrop]. */
    internal fun encoded(): String = "${width}x$height${gravity.letter}"
}

/** The inverse of [Crop.encoded]. Null for `-`, which is how "no crop" is written. */
internal fun parseCrop(encoded: String): Crop? {
    if (encoded == NO_CROP) {
        return null
    }
    val gravity = Gravity.entries.firstOrNull { it.letter == encoded.last() } ?: return null
    val ratio = encoded.dropLast(1).split("x")
    if (ratio.size != 2) {
        return null
    }
    val width = ratio[0].toIntOrNull() ?: return null
    val height = ratio[1].toIntOrNull() ?: return null
    return if (width > 0 && height > 0) Crop(width, height, gravity) else null
}

/** How "no crop" is written where something has to be. */
internal const val NO_CROP: String = "-"

internal fun Crop?.encodedOrNone(): String = this?.encoded() ?: NO_CROP

/**
 * One way an image is rendered: the widths it is offered in, how wide it is on the page, what shape it is cut to,
 * and the formats it is served in. A template has one of these of its own, and one more for every alternative
 * declared with [ImageTemplate.Builder.on]; every rendition of one template shares its formats.
 */
public class ImageRendition internal constructor(
    public val name: String,
    public val media: String?,
    public val widths: Set<Int>,
    public val sizes: String,
    public val crop: Crop?,
    public val formats: Set<String>,
) {

    init {
        require(name.matches(ImageTemplate.validName)) { "'$name' is not a valid name: ${ImageTemplate.validName}" }
        require(widths.isNotEmpty()) { "The image rendition '$name' has no widths, so it has nothing to render" }
        require(formats.isNotEmpty()) { "The image rendition '$name' has no formats, so it has nothing to serve" }
        require(widths.all { it > 0 }) { "The widths of '$name' must be positive" }
        require(sizes.isNotBlank()) { "The rendition '$name' must say how wide it is rendered" }
    }

    /** The ladder, narrowest first. */
    internal val ladder: List<Int> = widths.sorted()

    /** The last URL segment of one variant of this rendition. */
    internal fun segment(width: Int, format: String): String = "$name-$width.$format"

    override fun toString(): String = "ImageRendition($name)"
}

/**
 * One role an image plays on a page — a hero, a thumbnail, an avatar — and everything that follows from it: which
 * widths it is served in, how wide it is rendered, what shape it is cut to, and how eagerly it is fetched.
 *
 * Created by [ImagePlugin.template], which registers it: nothing can be rendered that the route will not serve.
 *
 * ```kotlin
 * val hero = images.template("hero", widths = setOf(640, 1280, 2560), sizes = "100vw", crop = Crop(16, 9)) {
 *     on("mobile", media = "(max-width: 600px)", widths = setOf(320, 640), sizes = "100vw", crop = Crop(4, 5))
 * }
 *
 * val (flower, cover) = klerk.read(context) {
 *     val flower = get(flowerID)
 *     flower to attachedData.metadata(flower.props.image.id)
 * }
 *
 * support.respondPage(call, flower.props.name.value) {
 *     image(hero, cover, alt = "A flower")
 * }
 * ```
 *
 * @param name identifies the template in the URL of every variant it renders — `hero-640.jpeg`. Lower-case letters,
 * digits and dashes. Renaming a template abandons what has been generated under the old name; that is a cache, so it
 * costs regeneration and nothing else.
 * @param widths the ladder this role is offered and served in. It is an allow-list as well as a `srcset`: a request
 * for any other width is a `404`. Nothing is generated until a browser asks for it, so widths that turn out to be
 * unused cost nothing.
 * @param sizes how wide the image is rendered, as the CSS `sizes` attribute. `100vw` for something full-width,
 * `(max-width: 600px) 100vw, 600px` for a column, `48px` for an avatar. `auto, 48px` asks the browser to measure
 * the image itself and use `48px` where it cannot; `auto` is only honoured on a lazily loaded image, and needs
 * that fallback because the browsers that do not know it are still numerous.
 *
 * Left out, it is `auto, 100vw` — the browser measures the image, and falls back to the full viewport width where
 * it cannot — or plain `100vw` on an eager template, which a browser would never measure. That is a working
 * default rather than a good one: it is a fact about your stylesheet, so a thumbnail that is really 160 px wide
 * fetches the largest variant until you say so.
 * @param crop the shape every variant is cut to, or null to keep the image's own. `Crop(1, 1)` is how an avatar
 * stays square whatever was uploaded.
 *
 * Worth declaring when the slot's shape differs materially from what people upload, because then the file is the
 * shape of the box and `sizes` means what it says. `object-fit: cover` in the stylesheet is the better answer when
 * the shapes are close, since a crop is its own set of generated files. Size, shape and format are the only things
 * decided here: anything that does not change how many bytes travel — blur, grayscale, rounded corners, rotation —
 * belongs in CSS.
 * @param loading whether the browser may defer the fetch. Leave it lazy for everything except the image that is
 * visible without scrolling — deferring that one delays the page's largest paint.
 * @param fetchPriority `High` for the one image the page is about, null to say nothing.
 */
public class ImageTemplate<C : KlerkContext, V> internal constructor(
    public val default: ImageRendition,
    public val alternatives: List<ImageRendition>,
    public val loading: ImageLoading,
    public val fetchPriority: FetchPriority?,
    internal val images: ImagePlugin<C, V>,
) {

    public val name: String get() = default.name
    public val widths: Set<Int> get() = default.widths
    public val sizes: String get() = default.sizes
    public val crop: Crop? get() = default.crop

    /** The formats every rendition of this template is served in. */
    public val formats: Set<String> get() = default.formats

    init {
        (listOf(default) + alternatives).forEach { rendition ->
            if (usesAutoSizes(rendition.sizes)) {
                require(loading == ImageLoading.Lazy) {
                    "'${rendition.name}' asks for sizes=auto, which a browser only honours on a lazily loaded " +
                            "image. Leave loading lazy, or state the width yourself."
                }
                require(rendition.sizes.substringAfter(",", "").isNotBlank()) {
                    "'${rendition.name}' asks for sizes=auto without a fallback. Browsers that do not know 'auto' " +
                            "fall back to 100vw and fetch the largest variant, so write it as 'auto, 320px'."
                }
            }
        }
        alternatives.forEach {
            require(!it.media.isNullOrBlank()) { "The alternative '${it.name}' needs a media condition to be picked" }
        }
    }

    /**
     * Everything a page needs to render [container] in this role. Null if the actor may not read the value, or if it
     * is not an image the plugin handles.
     *
     * Called inside a read block, so that the model and what is known about its image come from one snapshot.
     */
    override fun toString(): String = "ImageTemplate($name)"

    /** Declares the alternatives of a template. See [ImagePlugin.template]. */
    public class Builder internal constructor(
        private val template: String,
        private val formats: Set<String>,
    ) {

        internal val alternatives: MutableList<ImageRendition> = mutableListOf()

        /**
         * An alternative for the viewports [media] matches — the same image, cut and sized for a layout the default
         * does not suit. The browser takes the first one whose condition holds, so declare the narrowest first.
         *
         * [name] is a suffix on the template's: `on("mobile", …)` of a `hero` is served as `hero-mobile-320.jpeg`.
         *
         * ```kotlin
         * on("mobile", media = "(max-width: 600px)", widths = setOf(320, 640), sizes = "100vw", crop = Crop(4, 5))
         * ```
         */
        public fun on(
            name: String,
            media: String,
            widths: Set<Int>,
            sizes: String,
            crop: Crop? = null,
        ) {
            require(media.isNotBlank()) { "The alternative '$name' needs a media condition to be picked" }
            alternatives.add(ImageRendition("$template-$name", media, widths, sizes, crop, formats))
        }
    }

    internal companion object {
        val validName: Regex = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
    }
}

/** Whether a `sizes` value asks the browser to measure the image, which it may only do as its first entry. */
internal fun usesAutoSizes(sizes: String): Boolean = sizes.substringBefore(',').trim().equals("auto", true)

/**
 * The same `sizes` without its `auto`, for an image that is not lazily loaded and would therefore have had it
 * ignored. `100vw` is what a browser falls back to when nothing is left, so saying it is no worse and is legible.
 */
internal fun withoutAutoSizes(sizes: String): String =
    sizes.substringAfter(",", "").trim().ifBlank { "100vw" }

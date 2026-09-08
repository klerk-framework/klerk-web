package dev.klerkframework.web.image

import mu.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Color
import java.awt.Image
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

private val log = KotlinLogging.logger {}

/**
 * The [ImageProcessor] for development: `javax.imageio`, so nothing has to be installed, and JPEG and PNG only.
 *
 * Downscaling is where nearly all of the bytes are — sending a 4000px photo to a 390px viewport is the expensive
 * mistake, not JPEG versus AVIF — so this really does solve the problem it is pointed at. It is still the wrong
 * choice for production, and says so in the log when it is constructed:
 *
 * - it decodes bytes an attacker chose inside your own JVM, where a decoder bug is your process;
 * - it writes neither WebP nor AVIF;
 * - [ImageLimits.timeout] frees the coroutine but cannot interrupt a decode already running inside ImageIO, so
 *   [ImageLimits.maxPixels] — checked before anything is decoded — is the protection that actually bites.
 *
 * Implement [ImageProcessor] against a transformer running beside the application to be rid of all three.
 *
 * @param quality the JPEG quality, 0..1.
 */
public class ImageIoProcessor(
    override val limits: ImageLimits = ImageLimits(),
    private val quality: Float = 0.82f,
) : ImageProcessor {

    override val outputFormats: Set<String> = setOf("jpeg", "png")

    init {
        log.warn {
            "ImageIoProcessor is meant for development: it decodes uploaded images inside this JVM, writes neither " +
                    "WebP nor AVIF, and cannot interrupt a decode that has started. Implement ImageProcessor " +
                    "against an image transformer running beside the application before this is worth attacking."
        }
    }

    override suspend fun probe(source: Path): ImageInfo? = withContext(Dispatchers.IO) {
        readerFor(source)?.use { (reader, _) ->
            val stored = ImageInfo(reader.getWidth(0), reader.getHeight(0))
            if (swapsDimensions(jpegOrientation(source))) ImageInfo(stored.height, stored.width) else stored
        }
    }

    /**
     * Decodes the whole image and writes it out again, which is the only way ImageIO can guarantee the result holds
     * no metadata: the writer is given none, so none can survive.
     *
     * The whole image is in memory at once here, unlike [render], which subsamples on the way in. That makes
     * [ImageLimits.maxPixels] the thing standing between an upload and the heap.
     */
    override suspend fun stripMetadata(source: Path, target: Path): ImageInfo {
        val format = formatOf(source) ?: throw ImageRefused("$source is not an image this processor can read")
        if (!outputFormats.contains(format)) {
            throw ImageRefused("$source is $format, which this processor cannot write back")
        }
        return withTimeout(limits.timeout) {
            withContext(Dispatchers.IO) {
                val orientation = jpegOrientation(source)
                // Int.MAX_VALUE asks for the image's own width, so nothing is scaled and nothing is subsampled.
                val decoded = decodeScaled(source, Int.MAX_VALUE, orientation, null)
                write(orient(decoded.image, orientation), target, format)
                decoded.displayed
            }
        }
    }

    /** What ImageIO calls this file's format, lowercased to match [outputFormats], or null if it cannot read it. */
    private fun formatOf(source: Path): String? =
        readerFor(source)?.use { (reader, _) ->
            val name = reader.formatName.lowercase()
            if (name == "jpg") "jpeg" else name
        }

    override suspend fun render(source: Path, target: Path, width: Int, format: String, crop: Crop?): ImageInfo {
        require(outputFormats.contains(format)) { "$format is not one of $outputFormats" }
        return withTimeout(limits.timeout) {
            withContext(Dispatchers.IO) {
                val orientation = jpegOrientation(source)
                val decoded = decodeScaled(source, width, orientation, crop)
                val oriented = orient(decoded.image, orientation)
                write(if (crop == null) oriented else cut(oriented, crop, width), target, format)
                decoded.displayed
            }
        }
    }

    /**
     * Decodes no more than necessary: ImageIO can drop rows and columns while reading, so a 4000px original destined
     * for 320px is never fully in memory. [width] is the width the image is wanted at once it has been turned the
     * right way up and cut to shape, which for a photograph taken sideways is a fraction of the stored height.
     */
    private fun decodeScaled(source: Path, width: Int, orientation: Int, crop: Crop?): Decoded {
        val handle = readerFor(source) ?: throw ImageRefused("$source is not an image this processor can read")
        return handle.use { (reader, _) ->
            val sourceWidth = reader.getWidth(0)
            val sourceHeight = reader.getHeight(0)
            val pixels = sourceWidth.toLong() * sourceHeight.toLong()
            if (pixels > limits.maxPixels) {
                throw ImageRefused(
                    "the image is ${sourceWidth}x$sourceHeight ($pixels pixels), and at most ${limits.maxPixels} " +
                            "are allowed"
                )
            }
            val displayedSize = if (swapsDimensions(orientation)) {
                ImageInfo(sourceHeight, sourceWidth)
            } else {
                ImageInfo(sourceWidth, sourceHeight)
            }
            // What one pixel of the output is worth in the source: with a crop, only part of the width survives.
            val usable = crop?.widthOf(displayedSize) ?: displayedSize.width
            val target = minOf(width, usable)
            val step = maxOf(1, usable / maxOf(1, target * 2))
            val params = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
            val decoded = reader.read(0, params)
            val ratio = target.toDouble() / usable
            val scaledWidth = maxOf(1, Math.round(sourceWidth * ratio).toInt())
            val scaledHeight = maxOf(1, Math.round(sourceHeight * ratio).toInt())
            val image = if (decoded.width == scaledWidth && decoded.height == scaledHeight) {
                decoded
            } else {
                resize(decoded, scaledWidth, scaledHeight)
            }
            Decoded(image, displayedSize)
        }
    }

    /** A decoded image together with the size of the original it came from, which [render] has to report. */
    private class Decoded(val image: BufferedImage, val displayed: ImageInfo)

    private fun resize(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        val result = BufferedImage(width, height, typeOf(image))
        result.createGraphics().apply {
            drawImage(scaled, 0, 0, null)
            dispose()
        }
        return result
    }

    /**
     * Cuts the largest rectangle of [crop]'s shape out of the image, placed where its gravity says, and scales it to
     * at most [width]. The image is already the right way up here, so the gravity means what the viewer sees.
     */
    private fun cut(image: BufferedImage, crop: Crop, width: Int): BufferedImage {
        val rectWidth = crop.widthOf(ImageInfo(image.width, image.height))
        val rectHeight = minOf(image.height, crop.heightFor(rectWidth))
        val x = when (crop.gravity) {
            Gravity.West -> 0
            Gravity.East -> image.width - rectWidth
            else -> (image.width - rectWidth) / 2
        }
        val y = when (crop.gravity) {
            Gravity.North -> 0
            Gravity.South -> image.height - rectHeight
            else -> (image.height - rectHeight) / 2
        }
        val cut = image.getSubimage(maxOf(0, x), maxOf(0, y), rectWidth, rectHeight)
        val served = minOf(width, rectWidth)
        // Always a copy: a sub-image shares its parent's raster, which not every writer is happy with.
        return resize(cut, served, crop.heightFor(served))
    }

    /** Turns the image the way the camera was held, so that what is served is what the photographer saw. */
    private fun orient(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) {
            return image
        }
        val swap = swapsDimensions(orientation)
        val width = if (swap) image.height else image.width
        val height = if (swap) image.width else image.height
        val result = BufferedImage(width, height, typeOf(image))
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        // AffineTransform(m00, m10, m01, m11, m02, m12) maps (x, y) to (m00x + m01y + m02, m10x + m11y + m12).
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)    // mirrored
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)     // upside down
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)    // mirrored, upside down
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)   // mirrored along the main diagonal
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)    // a quarter turn clockwise
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)     // mirrored along the other diagonal
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)    // a quarter turn anticlockwise
            else -> AffineTransform()
        }
        result.createGraphics().apply {
            drawImage(image, transform, null)
            dispose()
        }
        return result
    }

    /** Transparency survives into a PNG and is flattened onto white for a JPEG, which has no alpha channel. */
    private fun typeOf(image: BufferedImage): Int =
        if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB

    private fun flatten(image: BufferedImage): BufferedImage {
        if (!image.colorModel.hasAlpha()) {
            return image
        }
        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        result.createGraphics().apply {
            paint = Color.WHITE
            fillRect(0, 0, image.width, image.height)
            drawImage(image, 0, 0, null)
            dispose()
        }
        return result
    }

    private fun write(image: BufferedImage, target: Path, format: String) {
        val writer = ImageIO.getImageWritersByFormatName(format).asSequence().firstOrNull()
            ?: throw ImageRefused("no ImageIO writer for $format")
        // A JPEG writer cannot take a four-channel raster at all, so an image with alpha has to be flattened first.
        val writable = if (format == "jpeg") flatten(image) else image
        FileImageOutputStream(target.toFile()).use { output ->
            writer.output = output
            val params = writer.defaultWriteParam
            if (params.canWriteCompressed()) {
                params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                params.compressionQuality = quality
            }
            writer.write(null, javax.imageio.IIOImage(writable, null, null), params)
        }
        writer.dispose()
    }

    /** A reader positioned on [source], together with the stream it must outlive. */
    private fun readerFor(source: Path): ReaderHandle? {
        val stream = ImageIO.createImageInputStream(source.toFile()) ?: return null
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
        if (reader == null) {
            stream.close()
            return null
        }
        reader.setInput(stream, true, true)
        return ReaderHandle(reader, stream)
    }

    private class ReaderHandle(
        val reader: javax.imageio.ImageReader,
        val stream: javax.imageio.stream.ImageInputStream,
    ) : AutoCloseable {
        operator fun component1() = reader
        operator fun component2() = stream

        override fun close() {
            reader.dispose()
            stream.close()
        }
    }
}

/**
 * The EXIF orientation of a JPEG, 1..8, or 1 when there is none.
 *
 * A phone writes the photo the way the sensor read it and records how the camera was held; a decoder that ignores
 * that produces a sideways image and, worse, the wrong proportions. The JDK's JPEG reader is such a decoder, so this
 * reads the tag out of the file itself.
 *
 * Only the APP1 segment is parsed, and only far enough to find tag 0x0112. Anything unexpected means 1.
 */
private fun jpegOrientation(source: Path): Int =
    try {
        Files.newInputStream(source).buffered().use { readOrientation(it) }
    } catch (e: Exception) {
        1
    }

/** Whether an orientation exchanges the image's width and height. */
private fun swapsDimensions(orientation: Int): Boolean = orientation in 5..8

private const val ORIENTATION_TAG = 0x0112

/** The APP1 segment that holds EXIF starts with "Exif", two NULs, and then a TIFF header. */
private val EXIF_IDENTIFIER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

private fun isExif(payload: ByteArray): Boolean =
    EXIF_IDENTIFIER.indices.all { payload[it] == EXIF_IDENTIFIER[it] }

private fun readOrientation(stream: InputStream): Int {
    val input = DataInputStream(stream)
    if (input.readUnsignedShort() != 0xFFD8) {
        return 1
    }
    while (true) {
        var marker = input.readUnsignedByte()
        if (marker != 0xFF) {
            return 1
        }
        // Any number of fill bytes may precede the marker itself.
        while (marker == 0xFF) {
            marker = input.readUnsignedByte()
        }
        // Start of scan, or end of image: the metadata is behind us.
        if (marker == 0xDA || marker == 0xD9) {
            return 1
        }
        val length = input.readUnsignedShort()
        if (length < 2) {
            return 1
        }
        val payload = ByteArray(length - 2)
        input.readFully(payload)
        if (marker == 0xE1 && payload.size > 6 && isExif(payload)) {
            return orientationInTiff(payload, 6)
        }
    }
}

/** [bytes] from [start] is a TIFF header followed by IFD0. */
private fun orientationInTiff(bytes: ByteArray, start: Int): Int {
    val bigEndian = when (read16(bytes, start, true)) {
        0x4D4D -> true
        0x4949 -> false
        else -> return 1
    }
    if (read16(bytes, start + 2, bigEndian) != 42) {
        return 1
    }
    val ifd = start + read32(bytes, start + 4, bigEndian)
    val count = read16(bytes, ifd, bigEndian)
    for (i in 0 until count) {
        val entry = ifd + 2 + i * 12
        if (entry + 12 > bytes.size) {
            return 1
        }
        if (read16(bytes, entry, bigEndian) == ORIENTATION_TAG) {
            // A SHORT is written in the first two bytes of the four-byte value field.
            val value = read16(bytes, entry + 8, bigEndian)
            return if (value in 1..8) value else 1
        }
    }
    return 1
}

private fun read16(bytes: ByteArray, at: Int, bigEndian: Boolean): Int {
    if (at + 2 > bytes.size) {
        throw EOFException()
    }
    val first = bytes[at].toInt() and 0xFF
    val second = bytes[at + 1].toInt() and 0xFF
    return if (bigEndian) (first shl 8) or second else (second shl 8) or first
}

private fun read32(bytes: ByteArray, at: Int, bigEndian: Boolean): Int {
    val first = read16(bytes, at, bigEndian)
    val second = read16(bytes, at + 2, bigEndian)
    return if (bigEndian) (first shl 16) or second else (second shl 16) or first
}

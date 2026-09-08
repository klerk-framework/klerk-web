package dev.klerkframework.web

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Images that are awkward in the ways real uploads are awkward. */

/** A landscape image whose left half is red, so that a rotation is visible in a single pixel. */
fun landscape(width: Int = 800, height: Int = 400): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        paint = Color.BLUE
        fillRect(0, 0, width, height)
        paint = Color.RED
        fillRect(0, 0, width / 2, height)
        dispose()
    }
    return image
}

/** A portrait image whose top half is red and bottom half is blue, so that a gravity is visible in one pixel. */
fun topAndBottom(width: Int = 400, height: Int = 800): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        paint = Color.BLUE
        fillRect(0, 0, width, height)
        paint = Color.RED
        fillRect(0, 0, width, height / 2)
        dispose()
    }
    return image
}

fun writePng(directory: Path, name: String, image: BufferedImage): Path {
    val file = directory.resolve(name)
    Files.newOutputStream(file).use { ImageIO.write(image, "png", it) }
    return file
}

/**
 * Writes [image] as a JPEG carrying an EXIF orientation, the way a phone does: an APP1 segment holding a TIFF header
 * and one IFD entry for tag 0x0112.
 */
fun jpegWithOrientation(directory: Path, image: BufferedImage, orientation: Int): Path {
    val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    val exif = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,             // APP1, 34 bytes
        0x45, 0x78, 0x69, 0x66, 0x00, 0x00,                   // "Exif\0\0"
        0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,       // big-endian TIFF, IFD0 at offset 8
        0x00, 0x01,                                           // one entry
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,       // tag 0x0112, SHORT, count 1
        0x00, orientation.toByte(), 0x00, 0x00,               // the value, in the first two bytes
        0x00, 0x00, 0x00, 0x00,                               // no next IFD
    )
    val file = directory.resolve("orientation-$orientation.jpeg")
    Files.newOutputStream(file).use { out ->
        out.write(jpeg, 0, 2)                                 // SOI
        out.write(exif)
        out.write(jpeg, 2, jpeg.size - 2)
    }
    return file
}

/** A PNG whose left half is transparent and whose right half is red. */
fun transparentPng(directory: Path): Path {
    val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply {
        paint = Color.RED
        fillRect(200, 0, 200, 200)
        dispose()
    }
    val file = directory.resolve("logo.png")
    Files.newOutputStream(file).use { ImageIO.write(image, "png", it) }
    return file
}

fun isRed(rgb: Int): Boolean =
    ((rgb shr 16) and 0xFF) > 150 && ((rgb shr 8) and 0xFF) < 100 && (rgb and 0xFF) < 100

fun isBlue(rgb: Int): Boolean =
    ((rgb shr 16) and 0xFF) < 100 && ((rgb shr 8) and 0xFF) < 100 && (rgb and 0xFF) > 150

fun isWhite(rgb: Int): Boolean =
    ((rgb shr 16) and 0xFF) > 200 && ((rgb shr 8) and 0xFF) > 200 && (rgb and 0xFF) > 200

/**
 * Every file under [root], by name.
 *
 * The variant generator creates and deletes `partial-*.tmp` while it works, and a walk that meets one between
 * listing the directory and reading the entry throws. That is the test racing the thing it is waiting for, not a
 * failure, so look again.
 */
fun filesUnder(root: Path): List<String> {
    var last: java.io.UncheckedIOException? = null
    repeat(10) {
        try {
            return Files.walk(root).use { paths -> paths.map { it.fileName.toString() }.toList() }
        } catch (e: java.io.UncheckedIOException) {
            last = e
        }
    }
    throw checkNotNull(last)
}

/** The full path of the one file under [root] called [name], or null. Races like [filesUnder]. */
fun fileUnder(root: Path, name: String): Path? {
    var last: java.io.UncheckedIOException? = null
    repeat(10) {
        try {
            return Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == name }.findFirst() }
                .orElse(null)
        } catch (e: java.io.UncheckedIOException) {
            last = e
        }
    }
    throw checkNotNull(last)
}

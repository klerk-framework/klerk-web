package dev.klerkframework.web

import dev.klerkframework.web.image.Crop
import dev.klerkframework.web.image.Gravity
import dev.klerkframework.web.image.ImageIoProcessor
import dev.klerkframework.web.image.ImageLimits
import dev.klerkframework.web.image.ImageRefused
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What comes out of the JDK processor. Two things a phone or a designer will send that a naive downscaler gets
 * wrong: a photograph taken sideways, and a logo with a transparent background.
 */
class ImageIoProcessorTest {

    private val processor = ImageIoProcessor()
    private val directory: Path = Files.createTempDirectory("klerk-imageio")

    /** Whether the file holds an EXIF APP1 segment at all. */
    private fun hasExif(file: Path): Boolean {
        val bytes = Files.readAllBytes(file)
        val marker = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
        return (0..bytes.size - marker.size).any { at ->
            marker.indices.all { bytes[at + it] == marker[it] }
        }
    }

    @Test
    fun `stripMetadata removes what the camera wrote, and keeps the image the right way up`() = runBlocking {
        val sideways = jpegWithOrientation(directory, landscape(800, 400), 6)
        assertTrue(hasExif(sideways), "the fixture should carry EXIF to begin with")
        val stripped = directory.resolve("stripped.jpeg")

        val info = processor.stripMetadata(sideways, stripped)

        assertFalse(hasExif(stripped), "the stored image must not carry EXIF")
        // Orientation 6 is a quarter turn, so what was 800x400 is displayed 400x800 - and the pixels now say so,
        // which is why dropping the tag is safe.
        assertEquals(400, info.width)
        assertEquals(800, info.height)
        val written = assertNotNull(ImageIO.read(stripped.toFile()))
        assertEquals(400, written.width)
        assertEquals(800, written.height)
    }

    @Test
    fun `a rendered variant carries no metadata either`() = runBlocking {
        val sideways = jpegWithOrientation(directory, landscape(800, 400), 6)
        val variant = directory.resolve("variant.jpeg")

        processor.render(sideways, variant, 200, "jpeg", null)

        assertFalse(hasExif(variant), "a variant is served to anybody, so it may not carry the source's metadata")
    }

    @Test
    fun `stripMetadata refuses an image bigger than the limit allows`() = runBlocking {
        val small = ImageIoProcessor(ImageLimits(maxPixels = 1000))
        val source = jpegWithOrientation(directory, landscape(800, 400), 1)

        assertFailsWith<ImageRefused> { small.stripMetadata(source, directory.resolve("refused.jpeg")) }
        Unit
    }
    @Test
    fun `a photograph taken sideways is measured as it will be seen`() = runBlocking {
        val upright = jpegWithOrientation(directory, landscape(800, 400), 1)
        val sideways = jpegWithOrientation(directory, landscape(800, 400), 6)

        assertEquals(800, assertNotNull(processor.probe(upright)).width)
        assertEquals(400, assertNotNull(processor.probe(upright)).height)
        // the same stored pixels, but the camera was held on its side: 800x400 is displayed as 400x800
        assertEquals(400, assertNotNull(processor.probe(sideways)).width)
        assertEquals(800, assertNotNull(processor.probe(sideways)).height)
    }

    @Test
    fun `a photograph taken sideways is served the right way up`() = runBlocking {
        val source = jpegWithOrientation(directory, landscape(800, 400), 6)
        val target = directory.resolve("rotated.jpeg")

        processor.render(source, target, 200, "jpeg")

        val rendered = ImageIO.read(target.toFile())
        // 200 is the width the page asked for, of the image as it is seen - and 400x800 scaled to 200 is 200x400
        assertEquals(200, rendered.width)
        assertEquals(400, rendered.height)
        // a quarter turn clockwise puts the red half at the top
        assertTrue(isRed(rendered.getRGB(100, 20)), "expected red at the top")
        assertTrue(isBlue(rendered.getRGB(100, 380)), "expected blue at the bottom")
    }

    @Test
    fun `an upright photograph is left alone`() = runBlocking {
        val source = jpegWithOrientation(directory, landscape(800, 400), 1)
        val target = directory.resolve("upright.jpeg")

        processor.render(source, target, 200, "jpeg")

        val rendered = ImageIO.read(target.toFile())
        assertEquals(200, rendered.width)
        assertEquals(100, rendered.height)
        assertTrue(isRed(rendered.getRGB(20, 50)), "expected red on the left")
    }

    @Test
    fun `a crop is exactly the shape it was asked for`() = runBlocking {
        val source = writePng(directory, "wide.png", landscape(800, 400))
        val target = directory.resolve("square.png")

        processor.render(source, target, 200, "png", Crop(1, 1))

        val rendered = ImageIO.read(target.toFile())
        assertEquals(200, rendered.width)
        assertEquals(200, rendered.height)
    }

    @Test
    fun `gravity decides what survives`() = runBlocking {
        val source = writePng(directory, "tall.png", topAndBottom(400, 800))

        processor.render(source, directory.resolve("north.png"), 200, "png", Crop(1, 1, Gravity.North))
        processor.render(source, directory.resolve("south.png"), 200, "png", Crop(1, 1, Gravity.South))
        processor.render(source, directory.resolve("centre.png"), 200, "png", Crop(1, 1))

        // the top half is red, the bottom half blue, and a square keeps 400 of the 800 rows
        assertTrue(isRed(ImageIO.read(directory.resolve("north.png").toFile()).getRGB(100, 100)), "north kept the top")
        assertTrue(
            isBlue(ImageIO.read(directory.resolve("south.png").toFile()).getRGB(100, 100)),
            "south kept the bottom",
        )
        val centre = ImageIO.read(directory.resolve("centre.png").toFile())
        assertTrue(isRed(centre.getRGB(100, 10)), "the centre square straddles the join")
        assertTrue(isBlue(centre.getRGB(100, 190)), "the centre square straddles the join")
    }

    @Test
    fun `a crop of a sideways photograph cuts the edges the viewer sees`() = runBlocking {
        // stored 800x400 with the red half on the left; displayed as 400x800 with the red half at the top
        val source = jpegWithOrientation(directory, landscape(800, 400), 6)
        val target = directory.resolve("rotated-square.jpeg")

        processor.render(source, target, 200, "jpeg", Crop(1, 1, Gravity.North))

        val rendered = ImageIO.read(target.toFile())
        assertEquals(200, rendered.width)
        assertEquals(200, rendered.height)
        assertTrue(isRed(rendered.getRGB(100, 100)), "north of the turned image is the red half")
    }

    @Test
    fun `a crop never enlarges a small source`() = runBlocking {
        val source = writePng(directory, "small.png", landscape(300, 200))
        val target = directory.resolve("small-square.png")

        processor.render(source, target, 640, "png", Crop(1, 1))

        val rendered = ImageIO.read(target.toFile())
        // the largest square in 300x200 is 200x200, and nothing is invented to reach 640
        assertEquals(200, rendered.width)
        assertEquals(200, rendered.height)
    }

    @Test
    fun `transparency survives into a PNG`() = runBlocking {
        val target = directory.resolve("logo-100.png")

        processor.render(transparentPng(directory), target, 100, "png")

        val rendered = ImageIO.read(target.toFile())
        assertEquals(0, rendered.getRGB(10, 25) ushr 24, "the transparent half became opaque")
        assertEquals(255, rendered.getRGB(90, 25) ushr 24, "the opaque half became transparent")
    }

    @Test
    fun `transparency becomes white in a JPEG, not black`() = runBlocking {
        val target = directory.resolve("logo-100.jpeg")

        processor.render(transparentPng(directory), target, 100, "jpeg")

        val rendered = ImageIO.read(target.toFile())
        assertTrue(isWhite(rendered.getRGB(10, 25)), "expected a white background")
        assertTrue(isRed(rendered.getRGB(90, 25)), "expected the opaque half to survive")
    }
}

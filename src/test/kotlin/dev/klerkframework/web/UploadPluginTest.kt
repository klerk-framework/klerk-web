package dev.klerkframework.web

import dev.klerkframework.klerk.CustomIdentity
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import dev.klerkframework.web.upload.NoSuchUploadException
import dev.klerkframework.web.upload.UploadPlugin
import dev.klerkframework.web.upload.UploadStates
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * An upload is a model, so who may continue it and what happens when it is abandoned are decided the same way as for
 * anything else in Klerk. These tests are about the bytes: that they can arrive in any number of chunks, that the
 * model never claims more than is on disk, and that nobody else can touch them.
 */
class UploadPluginTest {

    private lateinit var stagingDir: Path

    private suspend fun setup(): Pair<Klerk<Context, MyCollections>, UploadPlugin<Context, MyCollections>> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        stagingDir = Files.createTempDirectory("klerk-upload-test")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val plugin = UploadPlugin<Context, MyCollections>(stagingDir)
        val klerk = Klerk.create(createConfig(collections).withPlugin(plugin))
        klerk.meta.start(installShutdownHook = false)
        return klerk to plugin
    }

    /** Two actors that are not each other, without needing the application's own user model. */
    private fun alice() = Context(CustomIdentity(id = null, externalId = 1))
    private fun bob() = Context(CustomIdentity(id = null, externalId = 2))

    @Test
    fun `An upload arrives in chunks and becomes Ready on the last one`() = runBlocking {
        val (klerk, plugin) = setup()
        val content = "the quick brown fox".toByteArray()

        val id = plugin.create(alice(), "fox.txt", "text/plain", content.size.toLong())
        assertEquals(UploadStates.Receiving.name, klerk.read(Context.system()) { get(id) }.state)
        assertEquals(0, plugin.offsetOf(alice(), id))

        val afterFirst = plugin.append(alice(), id, 0, content.copyOfRange(0, 10).inputStream())
        assertEquals(10, afterFirst)
        assertEquals(UploadStates.Receiving.name, klerk.read(Context.system()) { get(id) }.state)

        val afterSecond = plugin.append(alice(), id, 10, content.copyOfRange(10, content.size).inputStream())
        assertEquals(content.size.toLong(), afterSecond)
        assertEquals(UploadStates.Ready.name, klerk.read(Context.system()) { get(id) }.state)

        assertContentEquals(content, plugin.read(alice(), id).readAllBytes())
        klerk.meta.stop()
    }

    @Test
    fun `A chunk at the wrong offset is refused and nothing is written`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.bin", "application/octet-stream", 10)
        plugin.append(alice(), id, 0, "12345".byteInputStream())

        // the client thinks it is further along than it is
        assertFailsWith<IllegalStateException> { plugin.append(alice(), id, 9, "678".byteInputStream()) }

        assertEquals(5, plugin.offsetOf(alice(), id))
        assertEquals(5, Files.size(stagingDir.resolve(id.value.toString())))
        klerk.meta.stop()
    }

    @Test
    fun `A client cannot send more than it declared, and the upload survives the attempt`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.bin", "application/octet-stream", 4)

        assertFailsWith<IllegalStateException> {
            plugin.append(alice(), id, 0, "far too much".byteInputStream())
        }

        // a failed chunk leaves nothing behind, so the client can resume from where the model says it is
        assertEquals(0, plugin.offsetOf(alice(), id))
        assertEquals(0, Files.size(stagingDir.resolve(id.value.toString())))
        assertEquals(4, plugin.append(alice(), id, 0, "fits".byteInputStream()))
        assertContentEquals("fits".toByteArray(), plugin.read(alice(), id).readAllBytes())
        klerk.meta.stop()
    }

    @Test
    fun `An upload belongs to the actor that started it`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "private.txt", "text/plain", 5)

        assertFailsWith<NoSuchUploadException> { plugin.offsetOf(bob(), id) }
        assertFailsWith<NoSuchUploadException> { plugin.append(bob(), id, 0, "hello".byteInputStream()) }
        assertFailsWith<NoSuchUploadException> { plugin.delete(bob(), id) }
        klerk.meta.stop()
    }

    @Test
    fun `An upload that does not exist looks exactly like one that is not yours`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "private.txt", "text/plain", 5)
        plugin.delete(alice(), id)

        val gone = assertFailsWith<NoSuchUploadException> { plugin.offsetOf(alice(), id) }
        val notMine = assertFailsWith<NoSuchUploadException> {
            val other = plugin.create(alice(), "other.txt", "text/plain", 5)
            plugin.offsetOf(bob(), other)
        }
        assertEquals(gone.message!!.replace(Regex("[0-9]+"), "n"), notMine.message!!.replace(Regex("[0-9]+"), "n"))
        klerk.meta.stop()
    }

    @Test
    fun `Deleting an upload removes its bytes`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.txt", "text/plain", 5)
        plugin.append(alice(), id, 0, "hello".byteInputStream())
        assertTrue(Files.exists(stagingDir.resolve(id.value.toString())))

        plugin.delete(alice(), id)

        assertFalse(Files.exists(stagingDir.resolve(id.value.toString())))
        assertFailsWith<NoSuchUploadException> { plugin.offsetOf(alice(), id) }
        klerk.meta.stop()
    }

    @Test
    fun `Handing an upload to attached data consumes it`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.txt", "text/plain", 5)
        plugin.append(alice(), id, 0, "hello".byteInputStream())

        // the blob is unclaimed until a command stores it, which is what the lease is for
        plugin.toAttachedData(alice(), id, lease = 15.minutes)

        assertFalse(Files.exists(stagingDir.resolve(id.value.toString())), "the staged file is now the blob")
        assertFailsWith<NoSuchUploadException> { plugin.offsetOf(alice(), id) }
        klerk.meta.stop()
    }

    @Test
    fun `An incomplete upload cannot be handed to attached data`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.txt", "text/plain", 10)
        plugin.append(alice(), id, 0, "half".byteInputStream())

        assertFailsWith<IllegalStateException> { plugin.toAttachedData(alice(), id) }
        assertEquals(4, plugin.offsetOf(alice(), id), "the upload is untouched and can still be finished")
        klerk.meta.stop()
    }

    @Test
    fun `An incomplete upload cannot be read`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.txt", "text/plain", 10)
        plugin.append(alice(), id, 0, "half".byteInputStream())

        assertFailsWith<IllegalStateException> { plugin.read(alice(), id) }
        klerk.meta.stop()
    }

    @Test
    fun `The plugin registers its own sweep job`() = runBlocking {
        val (klerk, _) = setup()
        val types = klerk.config.jobs.types.keys.map { it.value }

        assertTrue(types.contains("klerk-web-upload-sweep"), "expected the sweep job to be registered, got $types")
        assertTrue(
            klerk.config.jobs.crons.any { it.type.name.value == "klerk-web-upload-sweep" },
            "expected a cron for the sweep job",
        )
        // and the application's own job configuration is untouched
        assertTrue(types.contains("my-job"), "the application's job types should still be registered, got $types")
        klerk.meta.stop()
    }

    @Test
    fun `The sweep deletes staged bytes that no upload refers to`() = runBlocking {
        val (klerk, plugin) = setup()
        val id = plugin.create(alice(), "f.txt", "text/plain", 5)
        plugin.append(alice(), id, 0, "hello".byteInputStream())

        // what a crash during an upload leaves behind: a file whose model is gone
        val orphan = stagingDir.resolve("999999")
        Files.write(orphan, "orphaned".toByteArray())
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(0))

        plugin.sweep()

        assertFalse(Files.exists(orphan))
        assertTrue(Files.exists(stagingDir.resolve(id.value.toString())), "a live upload must survive the sweep")
        klerk.meta.stop()
    }
}

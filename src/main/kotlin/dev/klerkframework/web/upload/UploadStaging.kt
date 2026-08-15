package dev.klerkframework.web.upload

import mu.KotlinLogging
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.name
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Thrown when a chunk arrives at an offset the staging file is not at. The client should ask for the offset again. */
internal class OffsetMismatch(val actual: Long, val expected: Long) :
    IllegalStateException("The upload is at offset $actual, not $expected")

/** Thrown when a client sends more bytes than the upload declared. */
internal class TooManyBytes(val limit: Long) : IllegalStateException("The upload is limited to $limit bytes")

/**
 * The bytes of uploads in progress, one append-only file per [Upload], named by its model id.
 *
 * Nothing here is authoritative: the model says how far an upload has got, and this only has to be able to answer
 * "what is actually on disk" so the two can be reconciled. Bytes are flushed to disk *before* the model records them,
 * so the recorded offset is never larger than what a crash would leave behind — a client that resumes may re-send a
 * chunk, but never skips one.
 */
internal class UploadStaging(private val root: Path) {

    init {
        Files.createDirectories(root)
        require(Files.isWritable(root)) { "The upload staging directory $root is not writable" }
    }

    /** The staging file of [id]. The name comes from the model id, never from anything the client sent. */
    fun pathFor(id: Int): Path = root.resolve(id.toString())

    /** How many bytes are on disk for [id]. */
    fun sizeOf(id: Int): Long = try {
        Files.size(pathFor(id))
    } catch (e: NoSuchFileException) {
        0
    }

    /**
     * Appends [source] to the staging file of [id].
     *
     * @param offset where the client believes the file ends. Must match what is actually there.
     * @param limit the largest the file may become, i.e. the declared size of the upload. Enforced while copying, so
     * a client that lies about the length of its body is cut off rather than allowed to fill the disk.
     * @return the size of the staging file afterwards.
     */
    fun append(id: Int, offset: Long, source: InputStream, limit: Long): Long {
        val path = pathFor(id)
        val actual = sizeOf(id)
        if (actual != offset) {
            throw OffsetMismatch(actual = actual, expected = offset)
        }
        Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            .use { out ->
                copyAtMost(source, out, limit - actual)
                out.flush()
            }
        fsync(path)
        return Files.size(path)
    }

    /** Reads the whole staged file, for handing to `attachedData.prepare`. */
    fun read(id: Int): InputStream = Files.newInputStream(pathFor(id))

    /**
     * Cuts the staging file back to [size].
     *
     * Used to undo a partial write: a chunk that fails halfway would otherwise leave the file ahead of what the model
     * records, and since the recorded offset is what the client resumes from, the upload could never continue.
     */
    fun truncateTo(id: Int, size: Long) {
        val path = pathFor(id)
        if (!Files.exists(path) || Files.size(path) <= size) {
            return
        }
        java.io.RandomAccessFile(path.toFile(), "rw").use {
            it.channel.truncate(size)
            it.channel.force(true)
        }
    }

    fun delete(id: Int) {
        Files.deleteIfExists(pathFor(id))
    }

    /**
     * The SHA-256 of the bytes between [from] and [to] of the staging file.
     *
     * Checking a chunk where it landed, rather than while it is in memory, means a client cannot make the server
     * hold an arbitrarily large chunk just by promising a checksum for it.
     */
    fun digestRange(id: Int, from: Long, to: Long): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(pathFor(id), StandardOpenOption.READ).use { channel ->
            channel.position(from)
            val buffer = java.nio.ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            var remaining = to - from
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(remaining, buffer.capacity().toLong()).toInt())
                val read = channel.read(buffer)
                if (read <= 0) {
                    break
                }
                buffer.flip()
                digest.update(buffer)
                remaining -= read
            }
        }
        return digest.digest()
    }

    /**
     * Deletes staging files that no upload refers to any more.
     *
     * A file always gets its model first, so a file without one is left over from a crash or from an upload whose
     * model has expired. [olderThan] keeps the sweep off files that are being written right now.
     */
    fun sweep(live: Set<Int>, olderThan: Instant) {
        Files.newDirectoryStream(root).use { files ->
            files.forEach { file ->
                val id = file.name.toIntOrNull()
                if (id != null && live.contains(id)) {
                    return@forEach
                }
                if (Files.getLastModifiedTime(file).toInstant().toEpochMilli() >= olderThan.toEpochMilliseconds()) {
                    return@forEach
                }
                logger.info { "Deleting abandoned upload staging file $file" }
                runCatching { Files.deleteIfExists(file) }
                    .onFailure { logger.error(it) { "Could not delete $file" } }
            }
        }
    }

    /** The model records the new offset next, so the bytes have to survive a crash first. */
    private fun fsync(path: Path) {
        java.io.RandomAccessFile(path.toFile(), "rw").use { it.channel.force(true) }
    }

    private fun copyAtMost(source: InputStream, target: OutputStream, limit: Long) {
        if (limit <= 0) {
            throw TooManyBytes(limit)
        }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.read(buffer, 0, wanted)
            if (read < 0) {
                return
            }
            target.write(buffer, 0, read)
            remaining -= read
        }
        // The client had more to give than it declared. Everything written so far stays; the caller fails the request.
        if (source.read() >= 0) {
            throw TooManyBytes(limit)
        }
    }
}

package dev.klerkframework.web.image

import com.google.gson.Gson
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections

private val log = KotlinLogging.logger {}
private val gson = Gson()

/** What the generator learned about an image while it had it decoded. */
public data class ImageSidecar(
    val width: Int,
    val height: Int,
)

/**
 * The generated variants on disk.
 *
 * ```
 * variants/42-ab12cd/
 *   meta.json      {"width":4000,"height":3000}
 *   320.jpeg
 *   640.jpeg
 *   320-4x5n.jpeg  cut to 4:5, keeping the top
 * ```
 *
 * A file is named after what it holds rather than after the rendition that asked for it, so two templates wanting
 * the same width and shape share one file.
 *
 * A directory is named after the attached-data id *and* the content hash, so a recycled id can never inherit the
 * previous value's images. Everything here is a cache: losing it costs regeneration, nothing else. It is also
 * node-local, so an application running on more than one node has one of these per node.
 */
internal class VariantStore(private val root: Path) {

    /** Sidecars are read on every page render, so keep the recent ones in memory. */
    private val sidecars: MutableMap<String, ImageSidecar> =
        Collections.synchronizedMap(object : LinkedHashMap<String, ImageSidecar>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageSidecar>): Boolean = size > 1000
        })

    init {
        Files.createDirectories(root)
    }

    fun directoryFor(id: String, hash: String): Path = root.resolve(key(id, hash))

    fun variant(id: String, hash: String, width: Int, format: String, crop: Crop?): Path? =
        directoryFor(id, hash).resolve(fileName(width, format, crop)).takeIf { Files.isRegularFile(it) }

    /** `640.png`, or `640-4x5n.png` when the variant is cut to a shape. */
    private fun fileName(width: Int, format: String, crop: Crop?): String = "$width${cropSuffix(crop)}.$format"

    private fun cropSuffix(crop: Crop?): String = crop?.let { "-${it.encoded()}" } ?: ""

    fun sidecar(id: String, hash: String): ImageSidecar? {
        val key = key(id, hash)
        sidecars[key]?.let { return it }
        val file = root.resolve(key).resolve(SIDECAR)
        if (!Files.isRegularFile(file)) {
            return null
        }
        return try {
            gson.fromJson(Files.readString(file), ImageSidecar::class.java)?.also { sidecars[key] = it }
        } catch (e: Exception) {
            log.warn(e) { "Could not read the sidecar of $key" }
            null
        }
    }

    fun writeSidecar(id: String, hash: String, sidecar: ImageSidecar) {
        val directory = directoryFor(id, hash)
        Files.createDirectories(directory)
        writeAtomically(directory.resolve(SIDECAR)) { Files.writeString(it, gson.toJson(sidecar)) }
        sidecars[key(id, hash)] = sidecar
    }

    /** Where to write a variant before it is [publish]ed. */
    fun temporaryFor(id: String, hash: String): Path {
        val directory = directoryFor(id, hash)
        Files.createDirectories(directory)
        return Files.createTempFile(directory, "partial-", ".tmp")
    }

    /**
     * Moves a finished variant into place, so that a request never sees a half-written image.
     */
    fun publish(temporary: Path, id: String, hash: String, width: Int, format: String, crop: Crop?) {
        Files.move(
            temporary,
            directoryFor(id, hash).resolve(fileName(width, format, crop)),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** Every `id to hash` this store holds images for, for the sweep to reconcile against what still exists. */
    fun all(): List<Pair<String, String>> = directories().mapNotNull { split(it.path.fileName.toString()) }

    /** A directory name back into the id and hash it was made from. */
    private fun split(name: String): Pair<String, String>? {
        val dash = name.indexOf('-')
        return if (dash <= 0) null else name.substring(0, dash) to name.substring(dash + 1)
    }

    fun delete(id: String, hash: String) {
        val key = key(id, hash)
        sidecars.remove(key)
        val directory = root.resolve(key)
        if (!Files.isDirectory(directory)) {
            return
        }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    /** What this store currently holds, in bytes. */
    fun totalBytes(): Long = directories().sumOf { it.bytes }

    /**
     * Deletes whole images, newest-generated last, until the store is within [budget].
     *
     * A whole directory at a time: the sidecar and the variants of one image belong together, and dropping the
     * sidecar while keeping its variants would silently cost every page the image's dimensions. Ordered by when a
     * directory was last written rather than when it was last served, because access times cannot be relied on and
     * this is a cache whose miss costs one render.
     *
     * @return how many images were evicted.
     */
    fun evictTo(budget: Long): Int {
        var held = totalBytes()
        if (held <= budget) {
            return 0
        }
        var evicted = 0
        for (directory in directories().sortedBy { it.modified }) {
            if (held <= budget) {
                break
            }
            val (id, hash) = split(directory.path.fileName.toString()) ?: continue
            log.info { "Evicting the variants of $id to stay within $budget bytes" }
            delete(id, hash)
            held -= directory.bytes
            evicted++
        }
        return evicted
    }

    /** One image's directory, with what it costs and when it was last added to. */
    private class Held(val path: Path, val bytes: Long, val modified: Long)

    private fun directories(): List<Held> =
        Files.list(root).use { entries ->
            entries.toList().mapNotNull { path ->
                if (!Files.isDirectory(path)) return@mapNotNull null
                var bytes = 0L
                var modified = 0L
                // The generator writes and deletes temporary files here, so a file may vanish mid-walk.
                runCatching {
                    Files.list(path).use { files ->
                        files.forEach {
                            runCatching {
                                bytes += Files.size(it)
                                modified = maxOf(modified, Files.getLastModifiedTime(it).toMillis())
                            }
                        }
                    }
                }
                Held(path, bytes, modified)
            }
        }

    private fun writeAtomically(target: Path, write: (Path) -> Unit) {
        val temporary = Files.createTempFile(target.parent, "partial-", ".tmp")
        try {
            write(temporary)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun key(id: String, hash: String) = "$id-$hash"

    private companion object {
        const val SIDECAR = "meta.json"
    }
}

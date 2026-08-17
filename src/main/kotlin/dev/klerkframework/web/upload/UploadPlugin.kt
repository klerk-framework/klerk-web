package dev.klerkframework.web.upload

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.Config
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkPlugin
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.datatypes.BlobContainer
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobResult
import dev.klerkframework.klerk.job.JobStepArgs
import dev.klerkframework.klerk.job.JobType
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import mu.KotlinLogging
import java.io.InputStream
import java.nio.file.Path
import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.Problem
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Thrown for an upload that does not exist, and for one that belongs to somebody else.
 *
 * Deliberately the same exception for both: telling the two apart would let an actor learn which upload ids exist.
 */
public class NoSuchUploadException(message: String) : RuntimeException(message)

/** Thrown when a chunk does not match the checksum the client sent with it. The chunk is discarded. */
public class ChecksumMismatch(message: String) : RuntimeException(message)

/**
 * Resumable file upload.
 *
 * Bytes accumulate in [stagingDirectory] while an [Upload] model records who owns them and how far the upload has
 * got. Nothing becomes attached data until the application attaches it: at that point the staged file is handed to
 * `attachedData.prepare` and the resulting blob is claimed by the command that stores it on a model.
 *
 * The plugin only manages uploads. What may be uploaded, by whom and how large is decided by ordinary authorization
 * rules on [CreateUpload], which see the declared size before any byte is accepted.
 *
 * @param stagingDirectory where partial uploads are kept. Required, and independent of where blobs end up — an
 * application storing blobs in the database can still upload large files. Put it on the same filesystem as a
 * [dev.klerkframework.klerk.storage.FileBlobStore] to make the final step a rename rather than a copy.
 * @param lifetime how long an upload may sit unfinished, or finished but unused, before it is discarded.
 * @param sweepExpression how often the staging directory is reconciled against the models, as a cron expression.
 * Hourly by default.
 */
public class UploadPlugin<C : KlerkContext, V>(
    stagingDirectory: Path,
    private val lifetime: Duration = 24.hours,
    private val sweepExpression: String = "0 * * * *",
) : KlerkPlugin<C, V> {

    override val name: String = "Uploads"

    override val description: String =
        """Resumable file upload. Partial uploads are staged on disk and tracked by an Upload model, which
            |decides who may continue an upload and cleans itself up if the upload is abandoned.""".trimMargin()

    internal val staging: UploadStaging = UploadStaging(stagingDirectory)
    internal val views: ModelViews<Upload, C> = ModelViews()

    /** Where [dev.klerkframework.web.upload.uploadRoutes] mounted the endpoints, so that forms know where to post. */
    internal var mountedAt: String? = null

    private lateinit var klerk: Klerk<C, V>

    private val sweepJob = SweepStagingArea()

    override fun mergeConfig(previous: Config<C, V>): Config<C, V> {
        val managedModels = previous.managedModels.toMutableSet()
        managedModels.add(ManagedModel(Upload::class, uploadStateMachine(lifetime), views))
        return previous.copy(managedModels = managedModels).withJobs {
            register(sweepJob)
            cron(sweepJob, sweepExpression) { cursor = "" }
        }
    }

    override suspend fun start(klerk: Klerk<C, V>) {
        this.klerk = klerk
        sweep()
    }

    /**
     * Starts an upload and returns the id the client should send its chunks to.
     *
     * Runs as the calling actor, so the application's authorization rules decide whether this upload may begin, and
     * the upload belongs to that actor from then on.
     */
    public suspend fun create(
        context: C,
        filename: String,
        declaredContentType: String,
        declaredSize: Long,
    ): ModelID<Upload> {
        val result = klerk.handle(
            Command(
                event = CreateUpload,
                model = null,
                params = CreateUploadParams(
                    filename = UploadFilename(filename),
                    declaredContentType = UploadContentType(declaredContentType),
                    declaredSize = ByteCount(declaredSize),
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple()),
        )
        return requireNotNull(result.orThrow().primaryModel)
    }

    /**
     * Asks whether this actor would be allowed to start such an upload, without starting one.
     *
     * The path without JavaScript has no [Upload] to create — there is nothing to resume in a single request — but the
     * application's authorization rules on [CreateUpload] should still decide, or a rule like "members may upload at
     * most 10 MB" would silently apply to one path and not the other. Running the command as a dry run asks exactly
     * that question and persists nothing.
     *
     * @param declaredSize the best estimate available before the bytes are read. For a form submission that is the
     * request's `Content-Length`, which covers the whole request and so slightly over-estimates the file.
     * @return null if the upload would be allowed, otherwise the problem to report.
     */
    public suspend fun reasonUploadWouldBeRefused(
        context: C,
        filename: String,
        declaredContentType: String,
        declaredSize: Long,
    ): Problem? {
        val result = klerk.handle(
            Command(
                event = CreateUpload,
                model = null,
                params = CreateUploadParams(
                    filename = UploadFilename(filename),
                    declaredContentType = UploadContentType(declaredContentType),
                    declaredSize = ByteCount(declaredSize),
                ),
            ),
            context,
            ProcessingOptions(CommandToken.simple(), dryRun = true),
        )
        return when (result) {
            is CommandResult.Success -> null
            is CommandResult.Failure -> result.problems.firstOrNull()
        }
    }

    /** How much of [id] has arrived, i.e. where the client should resume. */
    public suspend fun offsetOf(context: C, id: ModelID<Upload>): Long =
        get(context, id).props.receivedBytes.valueWithoutAuthorization

    /**
     * Appends the next chunk.
     *
     * The bytes are written and flushed first, and only then does the model record them, so a crash costs the client
     * a re-send rather than leaving the model claiming bytes that were never stored.
     *
     * @param offset where the client believes the upload ends. A mismatch throws [OffsetMismatch] and nothing is
     * written — the client should ask for the offset and resume from there.
     * @return the new offset.
     * @throws TooManyBytes if the chunk would take the upload past its declared size.
     */
    public suspend fun append(context: C, id: ModelID<Upload>, offset: Long, data: InputStream): Long =
        append(context, id, offset, data, verify = null)

    /**
     * Appends a chunk and checks it against the checksum the client sent, as tus's checksum extension describes.
     *
     * The chunk is verified where it landed rather than in memory, so a client cannot make the server buffer an
     * arbitrarily large chunk just by promising a checksum. A chunk that does not match is rolled back entirely.
     *
     * @param checksum the value of the `Upload-Checksum` header: an algorithm name and a Base64 digest.
     * @throws ChecksumMismatch if the digest does not match, or names an algorithm other than sha256.
     */
    public suspend fun appendVerified(
        context: C,
        id: ModelID<Upload>,
        offset: Long,
        data: InputStream,
        checksum: String,
    ): Long {
        val parts = checksum.trim().split(" ", limit = 2)
        if (!parts.first().equals("sha256", ignoreCase = true) || parts.size != 2) {
            throw ChecksumMismatch("Only sha256 checksums are supported, got '${parts.first()}'")
        }
        val expected = try {
            Base64.getDecoder().decode(parts[1])
        } catch (e: IllegalArgumentException) {
            throw ChecksumMismatch("The checksum is not Base64")
        }
        return append(context, id, offset, data) { written ->
            val actual = staging.digestRange(id.value, offset, written)
            if (!actual.contentEquals(expected)) {
                throw ChecksumMismatch("The chunk does not match the checksum the client sent")
            }
        }
    }

    private suspend fun append(
        context: C,
        id: ModelID<Upload>,
        offset: Long,
        data: InputStream,
        verify: ((Long) -> Unit)?,
    ): Long {
        val upload = get(context, id)
        // Anything that goes wrong from here on rolls the file back to where the model says the upload is. A file
        // left ahead of the model could never be resumed: the client resumes from the recorded offset, and that
        // offset would no longer match what is on disk.
        return try {
            val written = staging.append(
                id = id.value,
                offset = offset,
                source = data,
                limit = upload.props.declaredSize.valueWithoutAuthorization,
            )
            verify?.invoke(written)
            klerk.handle(
                Command(
                    event = RecordUploadedBytes,
                    model = id,
                    params = RecordUploadedBytesParams(ByteCount(written)),
                ),
                systemContext(),
                ProcessingOptions(CommandToken.simple()),
            ).orThrow()
            written
        } catch (e: Exception) {
            staging.truncateTo(id.value, offset)
            throw e
        }
    }

    /**
     * The staged bytes, for handing to `attachedData.prepare`.
     *
     * @throws IllegalStateException if the upload is not complete — an incomplete file is a truncated file, and
     * attaching one to a model would store something the user never finished sending.
     */
    public suspend fun read(context: C, id: ModelID<Upload>): InputStream {
        requireComplete(get(context, id), id)
        return staging.read(id.value)
    }

    /**
     * Turns a finished upload into attached data, ready to be stored on a model by the next command.
     *
     * With a [dev.klerkframework.klerk.storage.FileBlobStore] on the same filesystem as the staging directory this
     * moves the file rather than copying it, so the size of the upload stops mattering at this point. The upload's
     * model is deleted either way: its bytes now belong to the blob.
     *
     * The blob is unclaimed until a command stores it, so it comes with a [lease] rather than the usual minute.
     *
     * @throws IllegalStateException if the upload is not complete.
     */
    public suspend fun toAttachedData(
        context: C,
        id: ModelID<Upload>,
        lease: Duration = 1.minutes,
        declaration: ((AttachedBlobID) -> BlobContainer)? = null,
    ): AttachedBlobID {
        val upload = get(context, id)
        requireComplete(upload, id)
        val blob = klerk.attachedData.prepareFromFile(
            file = staging.pathFor(id.value),
            context = context,
            metadata = mapOf(
                // The client's word, kept for the application to check or to serve back — never trusted as fact.
                "filename" to upload.props.filename.valueWithoutAuthorization,
                "declaredContentType" to upload.props.declaredContentType.valueWithoutAuthorization,
            ),
            lease = lease,
        )
        // The property's steps — a virus scan, a disarm pass, a check of the contents — before anything can attach
        // the value. Running them here means the user waits for them; running them as the upload finishes instead is
        // what the Inspecting state is for, and is not built yet.
        declaration?.let { klerk.attachedData.process(it(blob), context) }

        // Whether the file was moved or copied, nothing needs it any more.
        klerk.handle(
            Command(event = DeleteUpload, model = id, params = null),
            systemContext(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()
        staging.delete(id.value)
        return blob
    }

    private fun requireComplete(upload: Model<Upload>, id: ModelID<Upload>) {
        check(upload.props.isComplete) {
            "The upload $id is not complete (${upload.props.receivedBytes.valueWithoutAuthorization} of " +
                    "${upload.props.declaredSize.valueWithoutAuthorization} bytes)"
        }
    }

    /** Discards an upload and its bytes. Called when a client terminates one, and once its bytes have been attached. */
    public suspend fun delete(context: C, id: ModelID<Upload>) {
        get(context, id)
        klerk.handle(
            Command(event = DeleteUpload, model = id, params = null),
            systemContext(),
            ProcessingOptions(CommandToken.simple()),
        ).orThrow()
        staging.delete(id.value)
    }

    /**
     * The upload [id], if it exists and belongs to the actor in [context].
     *
     * Read with a system context and checked here rather than left to the application's read rules: an application
     * should not have to write authorization for a model the plugin contributed, and ownership is the only thing that
     * decides who may continue an upload.
     */
    public suspend fun get(context: C, id: ModelID<Upload>): Model<Upload> {
        val upload = try {
            klerk.read(systemContext()) { getOrNull(id) }
        } catch (e: Exception) {
            null
        } ?: throw NoSuchUploadException("There is no upload $id")

        if (!upload.props.belongsTo(context.actor)) {
            // The same exception as "no such upload": which uploads exist is not something to disclose.
            throw NoSuchUploadException("There is no upload $id")
        }
        return upload
    }

    private fun systemContext(): C = klerk.config.systemContextProvider(SystemIdentity)

    /**
     * Reconciles the staging directory against the models, at most once per [sweepInterval].
     *
     * This is how abandoned bytes are cleaned up: an expired upload deletes its own model through a time trigger, and
     * the file it leaves behind is removed here. Doing it by reconciliation rather than in a hook means a crash
     * midway through an upload is cleaned up too.
     */
    internal suspend fun sweep() {
        // Qualified: inside a read block, `views` is the application's views, not the plugin's.
        val uploads = this@UploadPlugin.views.all
        val live = klerk.read(systemContext()) { list(uploads) }.map { it.id.value }.toSet()
        // Files younger than this may belong to an upload whose model is not visible to this read yet.
        val olderThan = klerk.config.clock.now().minus(10.minutes)
        runCatching { staging.sweep(live, olderThan) }
            .onFailure { logger.error(it) { "Could not sweep the upload staging directory" } }
    }

    /**
     * Removes staging files that no upload refers to any more.
     *
     * An expired upload deletes its own model through a time trigger, and this removes the file it left behind. Doing
     * it by reconciling the directory against the models, rather than in a hook, means bytes left by a crash are
     * cleaned up too.
     */
    private inner class SweepStagingArea : JobType.Local<String, C, V>() {
        override val name: JobName = JobName("klerk-web-upload-sweep")

        override suspend fun step(args: JobStepArgs.Local<String, C, V>): JobResult<String> {
            sweep()
            return JobResult.Success(log = listOf(args.info("Swept the upload staging directory")))
        }
    }
}

/** Whether this upload was started by [actor]. */
internal fun Upload.belongsTo(actor: ActorIdentity): Boolean =
    actorType.valueWithoutAuthorization == actor.type &&
            actorReference?.valueWithoutAuthorization == actor.id?.value &&
            actorExternalId?.valueWithoutAuthorization == actor.externalId

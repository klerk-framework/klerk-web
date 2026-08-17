package dev.klerkframework.web.upload

import dev.klerkframework.klerk.ArgForInstanceEvent
import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import kotlin.time.Duration

/**
 * A file being uploaded. Managed by [UploadPlugin]; you never create one yourself.
 *
 * The bytes are *not* here — they accumulate in the plugin's staging directory, and only become
 * [dev.klerkframework.klerk.AttachedBlobID] attached data when a command attaches them to one of your models. What
 * this model holds is everything needed to decide whether the next chunk may be written: who started the upload, how
 * large it claims to be, and how much of it has arrived.
 *
 * Being a model is what makes an upload safe to name in a form: it has an owner, an authorization rule, an audit
 * trail, and a time trigger that cleans it up if it is abandoned.
 */
public data class Upload(
    val filename: UploadFilename,
    /** What the client said the content is. Never trusted — re-derive from the bytes before believing it. */
    val declaredContentType: UploadContentType,
    /** What the client said the size is, used to decide when the upload is complete. */
    val declaredSize: ByteCount,
    val receivedBytes: ByteCount,
    /** [dev.klerkframework.klerk.ActorIdentity.type] of whoever created the upload. */
    val actorType: ActorType,
    /** [dev.klerkframework.klerk.ActorIdentity.id], when the actor is a model. */
    val actorReference: ActorReference?,
    /** [dev.klerkframework.klerk.ActorIdentity.externalId], when the actor has one. */
    val actorExternalId: ActorExternalId?,
) {
    /** True once every declared byte has arrived. */
    public val isComplete: Boolean
        get() = receivedBytes.valueWithoutAuthorization >= declaredSize.valueWithoutAuthorization
}

/**
 * The life of an [Upload]. There is no state for "attached": the model is deleted when its bytes become attached
 * data, since from then on the blob and its owning model are the record of it.
 */
public enum class UploadStates {
    /** Bytes are still arriving. */
    Receiving,

    /** Every declared byte has arrived. The upload may now be named in a form. */
    Ready,
}

/**
 * @param lifetime how long an upload may sit in one state before it is abandoned. Applies to both states, so a
 * completed upload that is never submitted is cleaned up on the same terms as one that stalled halfway.
 */
internal fun <C : KlerkContext, V> uploadStateMachine(lifetime: Duration): StateMachine<Upload, Enum<*>, C, V> =
    stateMachine {
        event(CreateUpload) {}
        event(RecordUploadedBytes) {}
        event(DeleteUpload) {}

        voidState {
            onEvent(CreateUpload) {
                createModel(UploadStates.Receiving, ::newUpload)
            }
        }

        state(UploadStates.Receiving) {
            onEvent(RecordUploadedBytes) {
                update(::withReceivedBytes)
                transitionTo(UploadStates.Ready, onCondition = ::completesTheUpload)
            }
            onEvent(DeleteUpload) {
                delete()
            }
            // An upload nobody finishes. Its staging file is removed by the plugin's sweep, which reconciles the
            // directory against the models rather than trusting a hook to have fired.
            after(lifetime) {
                delete()
            }
        }

        state(UploadStates.Ready) {
            onEvent(DeleteUpload) {
                delete()
            }
            after(lifetime) {
                delete()
            }
        }
    }

/** Issued by [UploadPlugin] when an upload starts. Not meant to be rendered by klerk-web. */
public object CreateUpload : VoidEventWithParameters<Upload, CreateUploadParams>(
    Upload::class,
    EventVisibility.CODE,
    CreateUploadParams::class,
)

/** Issued by [UploadPlugin] after each chunk has been written to the staging file. */
public object RecordUploadedBytes : InstanceEventWithParameters<Upload, RecordUploadedBytesParams>(
    Upload::class,
    EventVisibility.CODE,
    RecordUploadedBytesParams::class,
)

/** Issued by [UploadPlugin] when an upload is terminated by the client, or once its bytes have been attached. */
public object DeleteUpload : InstanceEventNoParameters<Upload>(Upload::class, EventVisibility.CODE)

/**
 * Parameters of [CreateUpload].
 *
 * Everything here comes from the client, which is why the size is *declared* rather than known. Write an
 * authorization rule on [CreateUpload] to decide who may upload what and how much — the declared size is available
 * there, before a single byte has been accepted.
 */
public data class CreateUploadParams(
    val filename: UploadFilename,
    val declaredContentType: UploadContentType,
    val declaredSize: ByteCount,
)

/** Parameters of [RecordUploadedBytes]. */
public data class RecordUploadedBytesParams(
    val receivedBytes: ByteCount,
)

private fun <C : KlerkContext, V> newUpload(args: ArgForVoidEvent<Upload, CreateUploadParams, C, V>): Upload {
    val params = args.command.params
    val actor = args.context.actor
    return Upload(
        filename = params.filename,
        declaredContentType = params.declaredContentType,
        declaredSize = params.declaredSize,
        receivedBytes = ByteCount(0),
        // Taken from the context, never from the parameters: an upload belongs to whoever started it.
        actorType = ActorType(actor.type),
        actorReference = actor.id?.let { ActorReference(it.value) },
        actorExternalId = actor.externalId?.let { ActorExternalId(it) },
    )
}

private fun <C : KlerkContext, V> withReceivedBytes(
    args: ArgForInstanceEvent<Upload, RecordUploadedBytesParams, C, V>
): Upload = args.model.props.copy(receivedBytes = args.command.params.receivedBytes)

/**
 * Decided from the parameters rather than the model, since the update above has not been applied to [args] yet.
 */
private fun <C : KlerkContext, V> completesTheUpload(
    args: ArgForInstanceEvent<Upload, RecordUploadedBytesParams, C, V>
): Boolean = args.command.params.receivedBytes.value >=
        args.model.props.declaredSize.value

/** The name the client gave the file. Only ever shown or offered as a download name — never used as a path. */
public class UploadFilename(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 255
    override val maxLines: Int = 1
}

/** A MIME type as claimed by the client. */
public class UploadContentType(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 255
    override val maxLines: Int = 1
}

/** A number of bytes. */
public class ByteCount(value: Long) : LongContainer(value) {
    override val min: Long = 0
    override val max: Long = Long.MAX_VALUE
}

/** The [dev.klerkframework.klerk.ActorIdentity.type] of the actor that created an upload. */
public class ActorType(value: Int) : IntContainer(value) {
    override val min: Int = 0
    override val max: Int = Int.MAX_VALUE
}

/**
 * The id of the model acting, when there is one. Deliberately not a `ModelID`: the plugin cannot know the type of an
 * application's user model, and an upload is too short-lived for the reference to be worth the coupling.
 */
public class ActorReference(value: Int) : IntContainer(value) {
    override val min: Int = 0
    override val max: Int = Int.MAX_VALUE
}

/** The external id of the actor, when it has one. */
public class ActorExternalId(value: Long) : LongContainer(value) {
    override val min: Long = Long.MIN_VALUE
    override val max: Long = Long.MAX_VALUE
}

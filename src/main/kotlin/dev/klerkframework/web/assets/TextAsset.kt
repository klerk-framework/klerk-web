package dev.klerkframework.web.assets

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.BlobContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.datatypes.BlobStep
import dev.klerkframework.klerk.datatypes.noPreAttachProcessing
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.web.assets.TextAssetStates.Updatable
import java.security.MessageDigest
import java.util.*

/** An asset served by [AssetsPlugin]. Managed by the plugin; you do not create these yourself. */
public data class TextAsset(
    val path: AssetPath,
    val contentType: AssetContentType,
    val hash: Base64hash,
    val brotli: CompressedAsset?,
)

/** The states of a [TextAsset]. */
public enum class TextAssetStates {
    Updatable,
}

internal fun <C : KlerkContext, V> createTextResourceStatemachine(): StateMachine<TextAsset, Enum<*>, C, V> =
    stateMachine {
        event(CreateTextAsset) {
        }
        event(DeleteTextAsset) {
        }

        voidState {
            onEvent(CreateTextAsset) {
                createModel(Updatable, ::newTextAsset)
            }
        }

        state(Updatable) {
            onEvent(DeleteTextAsset) {
                delete()
            }
        }

    }

/** Issued by [AssetsPlugin] at startup for each asset. Not meant to be rendered by klerk-web. */
public object CreateTextAsset : VoidEventWithParameters<TextAsset, CreateTextAssetParams>(
    TextAsset::class,
    EventVisibility.CODE,
    CreateTextAssetParams::class
)

/** Issued by [AssetsPlugin] when an asset is no longer part of the application. */
public object DeleteTextAsset : InstanceEventNoParameters<TextAsset>(TextAsset::class, EventVisibility.CODE)

/** Parameters of [CreateTextAsset]. */
public data class CreateTextAssetParams(
    val path: AssetPath,
    val contentType: AssetContentType,
    val hash: Base64hash,
    val brotli: CompressedAsset?
)

private fun <C : KlerkContext, V> newTextAsset(args: ArgForVoidEvent<TextAsset, CreateTextAssetParams, C, V>): TextAsset {
    val params = args.command.params
    return TextAsset(params.path, params.contentType, params.hash, params.brotli)
}

/** The path of an asset, relative to `src/main/resources/assets`. */
public class AssetPath(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 200
    override val maxLines: Int = 1
}

/** The MIME type an asset is served with. */
public class AssetContentType(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 50
    override val maxLines: Int = 1
}


/**
 * A shortened hash of an asset's content, encoded in Base64.
 */
public class Base64hash(value: String) : StringContainer(value) {
    override val minLength: Int = 11
    override val maxLength: Int = 11
    override val maxLines: Int = 1

    public companion object {
        private val md = MessageDigest.getInstance("SHA-256")
        public fun from(string: String): Base64hash {
            val digest = md.digest(string.toByteArray())
            return Base64hash(Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(11))
        }
    }
}

/**
 * The Brotli-compressed bytes of an asset.
 *
 * Declares nothing beyond being a blob: what it holds is compressed output produced by this plugin, not something a
 * user uploaded, so there is no type to insist on and nobody to keep out.
 */
public class CompressedAsset(id: AttachedBlobID) : BlobContainer(id) {
    override val preAttachSteps: List<BlobStep> = listOf(::noPreAttachProcessing)
}

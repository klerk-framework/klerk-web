package dev.klerkframework.web.assets

import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.BinaryKeyValueID
import dev.klerkframework.klerk.KeyValueID
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.web.assets.TextAssetStates.Updatable
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

public data class TextAsset(
    val path: AssetPath,
    val contentType: AssetContentType,
    val hash: Base64hash,
    val brotli: BinaryKeyValueID?,
)

public enum class TextAssetStates {
    Updatable,
}

internal fun <C: KlerkContext, V> createTextResourceStatemachine(): StateMachine<TextAsset, Enum<*>, C, V> =
    stateMachine {
        event(CreateTextAsset) {
        }

        voidState {
            onEvent(CreateTextAsset) {
                createModel(Updatable, ::newTextAsset)
            }
        }

        state(Updatable) {
        }

    }

public object CreateTextAsset : VoidEventWithParameters<TextAsset, CreateTextAssetParams>(TextAsset::class, true, CreateTextAssetParams::class)

public data class CreateTextAssetParams(
    val path: AssetPath,
    val contentType: AssetContentType,
    val hash: Base64hash,
    val brotli: BinaryKeyValueID?
)

private fun <C: KlerkContext, V> newTextAsset(args: ArgForVoidEvent<TextAsset, CreateTextAssetParams, C, V>): TextAsset {
    val params = args.command.params
    return TextAsset(params.path, params.contentType, params.hash, params.brotli)
}

public class AssetPath(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 200
    override val maxLines: Int = 1
}

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

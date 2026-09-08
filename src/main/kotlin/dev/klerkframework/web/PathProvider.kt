package dev.klerkframework.web

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.ModelID
import io.ktor.http.*
import kotlin.reflect.KClass

/**
 * Builds the URLs klerk-web uses to link between pages. It says nothing about what a page looks like - that is
 * [Layout].
 *
 * @param base Base path for the application, typically "/"
 * @param prefix Prefix for collections, e.g. "admin/"
 */
public interface PathProvider {
    public val base: String
    public val prefix: String
    public val assetsBase: String
    public val autoButtons: String

    /** Where [dev.klerkframework.web.attached.attachedDataRoutes] is mounted. */
    public val attachedDataBase: String get() = "${base}_attached"
    public fun pathForCollection(kClass: KClass<out Any>): String {
        return base + prefix + (kClass.simpleName?.lowercase() ?: error("KClass.simpleName cannot be null"))
    }

    /**
     * The path to the detail view of a single model, or null if there is no detail view for [kClass]. Callers must
     * render plain text instead of a link when this returns null, and must not register a route for it.
     */
    public fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String?

    /**
     * As [pathForItem], but takes the id as a string so that a route pattern (e.g. "{id}") can be built.
     */
    public fun pathForItem(kClass: KClass<out Any>, id: String): String?
    public fun assetPath(resource: String): String = "$assetsBase/$resource"

    /**
     * The URL of a piece of attached data, as served by [dev.klerkframework.web.attached.attachedDataRoutes].
     *
     * The hash is part of the URL and not decoration: attached-data ids are recycled once the data they referred to
     * has been deleted, so an id alone is not a safe cache key. Get it from
     * `klerk.attachedData.getMetadata(id, context).hash`.
     *
     * @param filename an optional last segment, ignored when serving. Use it to give a download a human name.
     */
    public fun attachedDataPath(id: AttachedBlobID, hash: String, filename: String? = null): String =
        attachedDataPath("$id", hash, filename)

    /** As [attachedDataPath], for an attached string. Blobs and strings are served by the same route. */
    public fun attachedDataPath(id: AttachedStringID, hash: String, filename: String? = null): String =
        attachedDataPath("$id", hash, filename)

    /** As [attachedDataPath], for a value whose kind is not known. */
    public fun attachedDataPath(id: AttachedDataID, hash: String, filename: String? = null): String =
        attachedDataPath("$id", hash, filename)

    /** As [attachedDataPath], taking the id and the hash from the metadata that carries both. */
    public fun attachedDataPath(metadata: AttachedDataMetadata, filename: String? = null): String =
        attachedDataPath("${metadata.id}", metadata.hash, filename)

    private fun attachedDataPath(id: String, hash: String, filename: String?): String =
        "$attachedDataBase/$id/$hash" + (filename?.let { "/${it.encodeURLPathPart()}" } ?: "")

    public fun withPrefix(): String = "$base$prefix"
}

/** Paths of the form `base + prefix + modelname` and `base + prefix + modelname/id`. */
public data class DefaultPathProvider(
    public override val base: String = "/",
    public override val prefix: String = "",
) : PathProvider {

    override val assetsBase: String = "${base}_assets"

    override val autoButtons: String = "${base}_autobuttons"

    override fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String? {
        return "${pathForCollection(kClass)}/${id.value}"
    }

    override fun pathForItem(kClass: KClass<out Any>, id: String): String? {
        return "${pathForCollection(kClass)}/$id"
    }

    init {
        require(base.startsWith("/")) { "Base path must start with /" }
        require(base.endsWith("/")) { "Base path must end with /" }
        require(prefix.isEmpty() || !prefix.startsWith("/")) { "Prefix must not start with /" }
        require(prefix.isEmpty() || prefix.endsWith("/")) { "Prefix must end with /" }
    }
}

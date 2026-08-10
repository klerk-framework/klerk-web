package dev.klerkframework.web

import dev.klerkframework.klerk.ModelID
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

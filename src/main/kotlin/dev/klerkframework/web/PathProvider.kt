package dev.klerkframework.web

import dev.klerkframework.klerk.ModelID
import dev.klerkframework.web.assets.CssAsset
import kotlin.reflect.KClass

/**
 * This interface provides path generation for collections and individual items. Used by various klerk-web
 * components to generate URLs for navigation and linking within the application.
 */
public interface PathProvider {
    public val base: String
    public val prefix: String
    public val assetsBase: String
    public val externalCssPath: String?
    public val css: CssAsset?
    public fun pathForCollection(kClass: KClass<out Any>): String {
        return base + prefix + (kClass.simpleName?.lowercase() ?: error("KClass.simpleName cannot be null"))
    }
    public fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String
    public fun pathForItem(kClass: KClass<out Any>, id: String): String
    public fun cssUrl(): String? = externalCssPath ?: if (css != null) "$assetsBase/${css!!.getPathAndHash()}" else null
    public fun assetPath(resource: String): String = "$assetsBase/$resource"
    public fun withPrefix(): String = "$base$prefix"
}

public data class DefaultPathProvider(
    public override val base: String = "/",
    public override val prefix: String = "",
    override val externalCssPath: String? = null,
    override val css: CssAsset? = null
) : PathProvider {

    override val assetsBase: String = "${base}assets"

    override fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String {
        return "${pathForCollection(kClass)}/${id.value}"
    }

    override fun pathForItem(kClass: KClass<out Any>, id: String): String {
        return "${pathForCollection(kClass)}/$id"
    }

    init {
        require(base.startsWith("/")) { "Base path must start with /" }
        require(base.endsWith("/")) { "Base path must end with /" }
        require(prefix.isEmpty() || !prefix.startsWith("/")) { "Prefix must not start with /" }
        require(prefix.isEmpty() || prefix.endsWith("/")) { "Prefix must end with /" }
        require(externalCssPath == null || externalCssPath.startsWith("https://")) { "CSS path must start with https://" }
        require(!(css != null && externalCssPath != null)) { "Cannot specify both externalCssPath and cssResource" }
    }
}

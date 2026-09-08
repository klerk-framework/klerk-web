package dev.klerkframework.web

import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.web.attached.defaultAttachedDataCacheControl
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.html.*
import mu.KotlinLogging
import kotlin.reflect.KClass

private val log = KotlinLogging.logger {}

/**
 * Wires the building blocks together: a list page and a detail page for each model you name, plus AutoButtons and the
 * Admin UI. The fastest way to get a working UI - see
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/introduction.md).
 *
 * Every part of this can be assembled by hand instead; see [WebSupport], [ModelListPage], [ModelDetailPage],
 * [TableTemplate], [FormTemplate] and [AutoButtons].
 *
 * @param canSeeAdminUI gates the Admin UI's operations pages. It is an internal tool - see [AdminUI].
 * @param eventFilter which events to generate forms for, see [AutoButtons].
 * @param attachedDataCacheControl the `Cache-Control` for attached data, see [WebSupport].
 */
public class KlerkWeb<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C,
    canSeeAdminUI: suspend (C) -> Boolean,
    public val pathProvider: PathProvider = DefaultPathProvider(),
    public val adminPathProvider: PathProvider = DefaultPathProvider(pathProvider.base, "admin/"),
    layout: Layout = Layout(assetsBase = pathProvider.assetsBase),
    classProvider: CssClassProvider? = null,
    eventFilter: (EventReference) -> Boolean = { true },
    attachedDataCacheControl: (AttachedDataMetadata) -> String = defaultAttachedDataCacheControl,
    private val useTableForDetails: Boolean = false,
) {
    /** What every generated page is built from. Pass it to your own blocks so they match. */
    public val support: WebSupport<C, V> =
        WebSupport(klerk, contextProvider, pathProvider, layout, classProvider, eventFilter, attachedDataCacheControl)

    public val autoButtons: AutoButtons<C, V> get() = support.autoButtons

    private val adminUI: AdminUI<C, V> = AdminUI(
        support.withPathProvider(adminPathProvider),
        canSeeAdminUI = canSeeAdminUI,
        showOptionalParameters = { false },
    )

    internal fun nav(
        translator: (ManagedModel<*, *, C, V>) -> String,
        models: Set<KClass<*>>,
    ): FlowContent.() -> Unit = {
        nav {
            ul {
                klerk.spec.managedModels.filter { it.kClass in models }.sortedBy { it.kClass.simpleName }.forEach { model ->
                    li {
                        a(href = pathProvider.pathForCollection(model.kClass)) { +translator(model) }
                    }
                }
            }
        }
    }

    internal fun registerInto(route: Route, models: Set<KClass<*>>) {
        route.autoButtonsRoutes(support.autoButtons)
        route.adminUiRoutes(adminUI)
        klerk.spec.managedModels.filter { it.kClass in models }.forEach { model ->
            val humanName = camelCaseToPretty(model.kClass.simpleName ?: "")
            log.info { "Registering route: ${pathProvider.pathForCollection(model.kClass)}" }
            route.modelListRoutes(
                ModelListPage<Any, C, V>(
                    model.kClass, support, pathProvider.pathForCollection(model.kClass), humanName,
                )
            )
            pathProvider.pathForItem(model.kClass, "{id}")?.let {
                log.info { "Registering route: $it" }
            }
            route.modelDetailRoutes(
                ModelDetailPage<Any, C, V>(model.kClass, support, humanName, useTable = useTableForDetails)
            )
        }
    }
}

/**
 * The AutoButtons and Admin UI routes, plus a list route and a detail route for each model in [models]. [models] is
 * empty by default, so nothing model-specific is generated until you name a model; build the pages for the rest
 * yourself. When [PathProvider.pathForItem] returns null for a model in [models], its detail route is skipped and the
 * list page renders plain text instead of dead links.
 *
 * ```kotlin
 * routing {
 *     klerkWebRoutes(klerkWeb, setOf(Author::class, Book::class))
 * }
 * ```
 */
public fun <C : KlerkContext, V> Route.klerkWebRoutes(
    klerkWeb: KlerkWeb<C, V>,
    models: Set<KClass<*>> = emptySet(),
): Unit = klerkWeb.registerInto(this, models)

/** A `<nav>` with a link to the list page of each model in [models]. Pass the same set as [klerkWebRoutes]. */
public fun <C : KlerkContext, V> FlowContent.modelsNav(
    klerkWeb: KlerkWeb<C, V>,
    translator: (ManagedModel<*, *, C, V>) -> String = { it.kClass.simpleName ?: "?" },
    models: Set<KClass<*>> = emptySet(),
) {
    apply(klerkWeb.nav(translator, models))
}

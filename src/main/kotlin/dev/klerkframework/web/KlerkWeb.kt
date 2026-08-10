package dev.klerkframework.web

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.misc.camelCaseToPretty
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.html.*
import mu.KotlinLogging
import kotlin.reflect.KClass

private val log = KotlinLogging.logger {}

/**
 * Wires the building blocks together: a list page and a detail page for every managed model, plus AutoButtons and the
 * Admin UI. The fastest way to get a working UI - see
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/introduction.md).
 *
 * Every part of this can be assembled by hand instead; see [WebSupport], [ModelListPage], [ModelDetailPage],
 * [TableTemplate], [FormTemplate] and [AutoButtons].
 *
 * @param canSeeAdminUI gates the Admin UI's operations pages. It is an internal tool - see [AdminUI].
 * @param eventFilter which events to generate forms for, see [AutoButtons].
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
    private val useTableForDetails: Boolean = false,
) {
    /** What every generated page is built from. Pass it to your own blocks so they match. */
    public val support: WebSupport<C, V> =
        WebSupport(klerk, contextProvider, pathProvider, layout, classProvider, eventFilter)

    public val autoButtons: AutoButtons<C, V> get() = support.autoButtons

    private val adminUI: AdminUI<C, V> = AdminUI(
        support.withPathProvider(adminPathProvider),
        canSeeAdminUI = canSeeAdminUI,
        showOptionalParameters = { false },
    )

    public fun generateNav(
        translator: (ManagedModel<*, *, C, V>) -> String = { it.kClass.simpleName ?: "?" },
        filter: (ManagedModel<*, *, C, V>) -> Boolean = { true },
    ): HtmlBlockTag.() -> Unit = {
        nav {
            ul {
                klerk.config.managedModels.filter(filter).sortedBy { it.kClass.simpleName }.forEach { model ->
                    li {
                        a(href = pathProvider.pathForCollection(model.kClass)) { +translator(model) }
                    }
                }
            }
        }
    }

    /**
     * A list route and a detail route for every managed model that [filter] admits, plus the AutoButtons and Admin UI
     * routes. Exclude a model to build its pages yourself; make [PathProvider.pathForItem] return null for it as
     * well, so that nothing links to a page that no longer exists.
     */
    public fun generateRoutes(
        filter: (ManagedModel<*, *, C, V>) -> Boolean = { true },
    ): Routing.() -> Unit = {
        apply(support.autoButtons.registerRoutes())
        apply(adminUI.registerRoutes())
        klerk.config.managedModels.filter(filter).forEach { model ->
            val humanName = camelCaseToPretty(model.kClass.simpleName ?: "")
            val listPage = ModelListPage<Any, C, V>(
                model.kClass, support, pathProvider.pathForCollection(model.kClass), humanName,
            )
            log.info { "Registering route: ${pathProvider.pathForCollection(model.kClass)}" }
            apply(listPage.registerRoutes())

            val detailPage = ModelDetailPage<Any, C, V>(
                model.kClass, support, humanName, useTable = useTableForDetails,
            )
            pathProvider.pathForItem(model.kClass, "{id}")?.let {
                log.info { "Registering route: $it" }
            }
            apply(detailPage.registerRoutes())
        }
    }
}

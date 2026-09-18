package dev.klerkframework.web
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.util.Locale
import kotlin.reflect.KClass

/**
 * An operations console: event log, jobs, metrics, the application log, the configuration and plugin pages, plus a
 * generated list and detail page for every managed model.
 *
 * The Admin UI is an **internal tool**. It is not a foundation for the UI your users see - build that from the
 * building blocks instead. See
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/admin-ui.md).
 *
 * [support] says where the admin pages live and what they look like. Give it a [PathProvider] with a prefix, e.g.
 * `DefaultPathProvider(prefix = "admin/")`.
 *
 * [canSeeAdminUI] decides whether the operations pages are shown at all; they answer 404 when it returns false. The
 * pages themselves are additionally subject to Klerk's own authorization rules.
 */
public class AdminUI<C : KlerkContext, V>(
    internal val support: WebSupport<C, V>,
    internal val canSeeAdminUI: suspend (C) -> Boolean,
    internal val customAfterEventButtonsOnDetailView: ((KClass<out Any>, Model<Any>) -> DIV.() -> Unit)? = null,
    internal val showOptionalParameters: (EventReference) -> Boolean = { (eventReference) -> true },
    internal val knownAlgorithms: Set<FlowChartAlgorithm<*, *>> = emptySet(),
    internal val createCommandPath: String = "/_createevent",
) {
    private val klerk: Klerk<C, V> = support.klerk
    private val contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C = support.contextProvider
    private val pathProvider: PathProvider = support.pathProvider
    private val autoButtons: AutoButtons<C, V> = support.autoButtons
    private val listViews: List<ModelListPage<out Any, C, V>>
    private val detailViews: List<ModelDetailPage<out Any, C, V>>
    private val createCommandsWithParams: List<LowCodeCreateEvent<C, V>>

    private val eventLogPath = "${pathProvider.withPrefix()}_eventlog"
    private val jobsPath = "${pathProvider.withPrefix()}_jobs"
    private val metricsPath = "${pathProvider.withPrefix()}_metrics"
    private val pluginsPath = "${pathProvider.withPrefix()}_plugins"
    private val logPath = "${pathProvider.withPrefix()}_log"
    private val documentationPath = "${pathProvider.withPrefix()}_documentation"
    private val settingsPath = "${pathProvider.withPrefix()}_settings"

    init {
        // TODO: remove and use autobuttons instead
        createCommandsWithParams =
            buildCreateEvents(klerk, createCommandPath, autoButtons.eventFilter) { event, kClass ->
                LowCodeCreateEvent(support, createCommandPath, event, kClass, autoButtons)
            }

        val pairs = klerk.specification.managedClasses.map { managedClass ->
            val humanName = managedClass.simpleName!!.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val modelPathPart = managedClass.simpleName!!.lowercase()
            val pathToList = "${pathProvider.withPrefix()}$modelPathPart"

            val listView = ModelListPage<Any, C, V>(managedClass, support, pathToList, humanName)
            val detailView = ModelDetailPage<Any, C, V>(
                managedClass,
                support,
                humanName,
                eventLogPath,
                customAfterEventButtonsOnDetailView,
            )
            Pair(listView, detailView)
        }
        listViews = pairs.map { it.first }
        detailViews = pairs.map { it.second }
    }

    internal fun registerInto(route: Route) {
        with(route) {
            for (listView in listViews) {
                modelListRoutes(listView)
            }
            for (detailView in detailViews) {
                modelDetailRoutes(detailView)
            }

            get(pathProvider.withPrefix()) {
                requireAdmin(call) {
                    renderMain(call)
                }
            }

            get(eventLogPath) {
                requireAdmin(call) {
                    renderEventLog(call, support, klerk)
                }
            }

            get("$eventLogPath/{id}") {
                requireAdmin(call) {
                    renderEventLogDetails(call, support, klerk)
                }
            }

            get(jobsPath) {
                requireAdmin(call) {
                    renderJobs(call, support, jobsPath, klerk)
                }
            }

            get("$jobsPath/types") {
                requireAdmin(call) {
                    renderJobTypes(call, support, jobsPath, klerk)
                }
            }

            get("$jobsPath/{id}") {
                requireAdmin(call) {
                    renderJobDetails(call, support, jobsPath, klerk)
                }
            }

            post("$jobsPath/{id}/cancel") {
                requireAdmin(call) {
                    handleJobCancel(call, support, jobsPath, klerk)
                }
            }

            post("$jobsPath/{id}/resume") {
                requireAdmin(call) {
                    handleJobResume(call, support, jobsPath, klerk)
                }
            }

            post("$jobsPath/{id}/delete") {
                requireAdmin(call) {
                    handleJobDelete(call, support, jobsPath, klerk)
                }
            }

            get(metricsPath) {
                requireAdmin(call) {
                    renderMetrics(call, support, metricsPath, klerk)
                }
            }

            get(settingsPath) {
                requireAdmin(call) {
                    renderSettings(call, support, klerk)
                }
            }

            get(documentationPath) {
                requireAdmin(call) {
                    renderDocumentation(call, support, klerk, documentationPath, knownAlgorithms)
                }
            }

            post("$documentationPath/functionInvocation") {
                requireAdmin(call) {
                    renderFunctionInvocation(call, support, klerk)
                }
            }

            get("$documentationPath/algorithms/{name}") {
                requireAdmin(call) {
                    renderAlgorithm(call, support, knownAlgorithms)
                }
            }

            get(pluginsPath) {
                requireAdmin(call) {
                    renderPlugins(call, support, klerk)
                }
            }

            get("${pathProvider.withPrefix()}plugin") {
                requireAdmin(call) {
                    renderPluginPage(call, support, klerk)
                }
            }

            for (plugin in klerk.specification.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>()) {
                plugin.registerExtraRoutes(this, pathProvider)
            }

            get(logPath) {
                requireAdmin(call) {
                    renderLog(call, support, klerk)
                }
            }
        }
    }

    private suspend fun renderMain(call: ApplicationCall) {
        support.respondPage(call, "Klerk Admin") {
            header {
                h1 { +"Klerk Admin" }

                nav {
                    span {
                        style = "margin: 10px;"
                        a(href = logPath) { button { +"Activity log" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = jobsPath) { button { +"Jobs" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = documentationPath) { button { +"Documentation" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = eventLogPath) { button { +"Event log" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = metricsPath) { button { +"Metrics" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = pluginsPath) { button { +"Plugins" } }
                    }

                    span {
                        style = "margin: 10px;"
                        a(href = settingsPath) { button { +"Settings" } }
                    }

                    for (plugin in klerk.specification.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>()) {
                        span {
                            style = "margin: 10px;"
                            a(href = "${pathProvider.withPrefix()}plugin?name=${plugin.name}") {
                                button { +plugin.page.buttonText }
                            }
                        }
                    }
                }
            }

            h2 { +"Data" }
            table {
                for (view in listViews) {
                    tr {
                        td {
                            a(href = view.pathToList) {
                                +view.humanName
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Responds 404 rather than 403, so that the existence of the Admin UI is not revealed to those who may not see it.
     */
    private suspend fun requireAdmin(call: ApplicationCall, block: suspend () -> Unit) {
        val context = contextProvider(call, klerk)
        if (!canSeeAdminUI(context)) {
            support.respondPage(call, "Not found", io.ktor.http.HttpStatusCode.NotFound) { +"Not found" }
            return
        }
        block()
    }
}

/** The Admin UI's routes. It is an internal tool - see [AdminUI]. */
public fun <C : KlerkContext, V> Route.adminUiRoutes(adminUI: AdminUI<C, V>) {
    adminUI.registerInto(this)
}

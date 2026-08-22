package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.AlgorithmDocumenter
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.util.*
import kotlin.reflect.KClass

/**
 * An operations console: audit log, jobs, metrics, the application log, the configuration and plugin pages, plus a
 * generated list and detail page for every managed model.
 *
 * The Admin UI is an **internal tool**. It is not a foundation for the UI your users see - build that from the
 * building blocks instead. See
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/admin-ui.md).
 *
 * @param support where the admin pages live and what they look like. Give it a [PathProvider] with a prefix, e.g.
 * `DefaultPathProvider(prefix = "admin/")`.
 * @param canSeeAdminUI decides whether the operations pages are shown at all; they answer 404 when it returns false.
 * The pages themselves are additionally subject to Klerk's own authorization rules.
 */
public class AdminUI<C : KlerkContext, V>(
    internal val support: WebSupport<C, V>,
    internal val canSeeAdminUI: suspend (C) -> Boolean,
    internal val customAfterEventButtonsOnDetailView: ((KClass<out Any>, Model<Any>) -> DIV.() -> Unit)? = null,
    internal val showOptionalParameters: (EventReference) -> Boolean = {(eventReference) -> true},
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

    private val auditPath = "${pathProvider.withPrefix()}_audit"
    private val jobsPath = "${pathProvider.withPrefix()}_jobs"
    private val metricsPath = "${pathProvider.withPrefix()}_metrics"
    private val pluginsPath = "${pathProvider.withPrefix()}_plugins"
    private val logPath = "${pathProvider.withPrefix()}_log"
    private val documentationPath = "${pathProvider.withPrefix()}_documentation"

    init {
        // TODO: remove and use autobuttons instead
        createCommandsWithParams =
            buildCreateEvents(klerk, createCommandPath, autoButtons.eventFilter) { event, kClass ->
                LowCodeCreateEvent(support, createCommandPath, event, kClass, autoButtons)
            }

        val pairs = klerk.specification.getManagedClasses().map { managedClass ->
            val humanName =
                managedClass.simpleName!!.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val modelPathPart = managedClass.simpleName!!.lowercase()
            val pathToList = "${pathProvider.withPrefix()}$modelPathPart"

            val listView = ModelListPage<Any, C, V>(managedClass, support, pathToList, humanName)
            val detailView = ModelDetailPage<Any, C, V>(
                managedClass, support, humanName, auditPath, customAfterEventButtonsOnDetailView
            )
            Pair(listView, detailView)
        }
        listViews = pairs.map { it.first }
        detailViews = pairs.map { it.second }

        AlgorithmDocumenter.setKnownAlgorithms(knownAlgorithms)
    }

    internal fun registerInto(route: Route): Unit = with(route) {
        listViews.forEach { modelListRoutes(it) }
        detailViews.forEach { modelDetailRoutes(it) }

        get(pathProvider.withPrefix()) {
            requireAdmin(call) {
                renderMain(call)
            }
        }

        get(auditPath) {
            requireAdmin(call) {
                renderAudit(call, support, klerk)
            }
        }

        get("$auditPath/{id}") {
            requireAdmin(call) {
                renderAuditDetails(call, support, klerk)
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

        get(documentationPath) {
            requireAdmin(call) {
                renderDocumentation(call, support, klerk, documentationPath)
            }
        }

        post("$documentationPath/functionInvocation") {
            requireAdmin(call) {
                renderFunctionInvocation(call, support, klerk)
            }
        }

        get("$documentationPath/algorithms/{name}") {
            requireAdmin(call) {
                renderAlgorithm(call, support, klerk)
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

        klerk.specification.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>().forEach { plugin ->
            plugin.registerExtraRoutes(this, pathProvider)
        }

        get(logPath) {
            requireAdmin(call) {
                renderLog(call, support, klerk)
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
                            a(href = logPath) { button { +"Log" } }
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
                            a(href = auditPath) { button { +"Audit Log" } }
                        }

                        span {
                            style = "margin: 10px;"
                            a(href = metricsPath) { button { +"Metrics" } }
                        }

                        span {
                            style = "margin: 10px;"
                            a(href = pluginsPath) { button { +"Plugins" } }
                        }

                        klerk.specification.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>().forEach { plugin ->
                            span {
                                style = "margin: 10px;"
                                a(href = "${pathProvider.withPrefix()}plugin?name=${plugin.name}") { button { +plugin.page.buttonText } }
                            }

                        }
                    }
                }

                h2 { +"Data" }
                table {
                    listViews.forEach { view ->
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

/**
 * A Klerk plugin that gives itself a page in the Admin UI and routes of its own. See
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/plugins.md).
 */
public interface AdminUIPluginIntegration<C : KlerkContext, V> : KlerkPlugin<C, V> {

    public val page: PluginPage<C, V>

    /** Called when the Admin UI registers its routes. Mount them under [PathProvider.withPrefix]. */
    public fun registerExtraRoutes(route: Route, pathProvider: PathProvider)

}

/** A plugin's own page in the Admin UI. */
public interface PluginPage<C : KlerkContext, V> {
    /** The text of the button in the Admin UI's navigation. */
    public val buttonText: String

    /** Responds with the page. Use [WebSupport.layout] so it matches the rest of the console. */
    public suspend fun respond(
        call: ApplicationCall,
        support: WebSupport<C, V>,
        klerk: Klerk<C, V>
    ): Unit

}

/** The Admin UI's routes. It is an internal tool - see [AdminUI]. */
public fun <C : KlerkContext, V> Route.adminUiRoutes(adminUI: AdminUI<C, V>): Unit = adminUI.registerInto(this)

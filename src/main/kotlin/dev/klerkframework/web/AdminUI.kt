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

public class AdminUI<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    internal val basePath: String,
    internal val contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C,
    internal val customAfterEventButtonsOnDetailView: ((KClass<out Any>, Model<Any>) -> DIV.() -> Unit)? = null,
    internal val showOptionalParameters: (EventReference) -> Boolean,
    internal val cssPath: String,
    internal val knownAlgorithms: Set<FlowChartAlgorithm<*, *>> = emptySet(),
    internal val createCommandPath: String = "/_createevent",
    internal val canSeeAdminUI: suspend (C) -> Boolean,
    internal val autoButtons: AutoButtons<C, V>

) {
    private val listViews: List<LowCodeList<out Any, C, V>>
    private val detailViews: List<LowCodeItemDetails<out Any, C, V>>
    private val createCommandsWithParams: List<LowCodeCreateEvent<C, V>>

    private val auditPath = "${basePath}/_audit"
    private val jobsPath = "${basePath}/_jobs"
    private val metricsPath = "${basePath}/_metrics"
    private val pluginsPath = "${basePath}/_plugins"
    private val logPath = "${basePath}/_log"
    private val documentationPath = "${basePath}/_documentation"

    init {
        // TODO: remove and use autobuttons instead
        createCommandsWithParams = klerk.config.managedModels.flatMap { managed ->
            managed.stateMachine.getAllEvents().filter { klerk.config.getParameters(it) != null }.map { event ->
                LowCodeCreateEvent(klerk, createCommandPath, event, managed.kClass)
            }
        }

        val pairs = klerk.config.getManagedClasses().map { managedClass ->
            val humanName =
                managedClass.simpleName!!.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val modelPathPart = managedClass.simpleName!!
            val pathToList = "${basePath}/$modelPathPart"

            val listView = LowCodeList<Any, C, V>(
                managedClass, this, createCommandsWithParams, modelPathPart, pathToList, humanName, klerk
            )
            val detailView = LowCodeItemDetails<Any, C, V>(
                managedClass, this, modelPathPart, humanName, klerk, auditPath
            )
            Pair(listView, detailView)
        }
        listViews = pairs.map { it.first }
        detailViews = pairs.map { it.second }
        listViews.forEach { it.initView(basePath) }
        detailViews.forEach { it.initView(basePath) }

        AlgorithmDocumenter.setKnownAlgorithms(knownAlgorithms)
    }

    public fun registerRoutes(): Routing.() -> Unit = {
        listViews.forEach { apply(it.registerRoutes()) }
        detailViews.forEach { apply(it.registerRoutes()) }

        get(basePath) {
            requireAdmin(call) {
                renderMain(call)
            }
        }

        get(auditPath) {
            requireAdmin(call) {
                renderAudit(call, this@AdminUI, basePath, klerk)
            }
        }

        get("$auditPath/{id}") {
            requireAdmin(call) {
                renderAuditDetails(call, this@AdminUI, klerk)
            }
        }

        get(jobsPath) {
            requireAdmin(call) {
                renderJobs(call, this@AdminUI, jobsPath, klerk)
            }
        }

        get("${jobsPath}/{id}") {
            requireAdmin(call) {
                renderJobDetails(call, this@AdminUI, klerk)
            }
        }

        get(metricsPath) {
            requireAdmin(call) {
                renderMetrics(call, this@AdminUI, metricsPath, klerk)
            }
        }

        get(documentationPath) {
            requireAdmin(call) {
                renderDocumentation(call, this@AdminUI, klerk, documentationPath)
            }
        }

        post("$documentationPath/functionInvocation") {
            requireAdmin(call) {
                renderFunctionInvocation(call, this@AdminUI, klerk)
            }
        }

        get("$documentationPath/algorithms/{name}") {
            requireAdmin(call) {
                renderAlgorithm(call, this@AdminUI, klerk)
            }
        }

        get(pluginsPath) {
            requireAdmin(call) {
                renderPlugins(call, this@AdminUI, klerk)
            }
        }

        get("${basePath}/plugin") {
            requireAdmin(call) {
                renderPluginPage(call, this@AdminUI, klerk)
            }
        }

        klerk.config.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>().forEach { plugin ->
            plugin.registerExtraRoutes(this, basePath)
        }

        get(logPath) {
            requireAdmin(call) {
                renderLog(call, this@AdminUI, klerk)
            }
        }


    }

    private suspend fun renderMain(call: ApplicationCall) {
        val actor = contextProvider(call, klerk)
        call.respondHtml {
            apply(lowCodeHtmlHead(cssPath))
            body {
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

                        klerk.config.plugins.filterIsInstance<AdminUIPluginIntegration<C, V>>().forEach { plugin ->
                            span {
                                style = "margin: 10px;"
                                a(href = "${basePath}/plugin?name=${plugin.name}") { button { +plugin.page.buttonText } }
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
    }

    private suspend fun requireAdmin(call: ApplicationCall, block: suspend () -> Unit) {
        val context = contextProvider(call, klerk)
        if (!canSeeAdminUI(context)) {
            call.respondHtml(status = io.ktor.http.HttpStatusCode.Forbidden) { body { +"Not authorized" } }
            return
        }
        block()
    }

}

public interface AdminUIPluginIntegration<C : KlerkContext, V> : KlerkPlugin<C, V> {

    public val page: PluginPage<C, V>
    public fun registerExtraRoutes(routing: Routing, basePath: String)

}

public interface PluginPage<C : KlerkContext, V> {
    public val buttonText: String
    public suspend fun render(
        call: ApplicationCall,
        config: AdminUI<C, V>,
        klerk: Klerk<C, V>
    ): Unit

}

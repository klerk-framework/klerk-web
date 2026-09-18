package dev.klerkframework.web
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ViewID
import dev.klerkframework.klerk.VoidEvent
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.ModelViews
import io.ktor.client.request.request
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.net.URLDecoder
import java.nio.charset.Charset
import kotlin.reflect.KClass

internal const val NON_BREAKING_SPACE = "&nbsp;"
internal const val NON_BREAKING_HYPHEN = "&#8209;"

/**
 * A generated list page for one model: a table of every instance, plus a button for each event that can create one.
 *
 * Register its route, or call [respond] from a route of your own.
 */
public class ModelListPage<T : Any, C : KlerkContext, V>(
    private val kClass: KClass<out Any>,
    private val support: WebSupport<C, V>,
    /** The path the list is served at. */
    public val pathToList: String,
    /** The title of the page. */
    public val humanName: String,
    private val renderListDetails: Boolean = false,
) {
    private val klerk: Klerk<C, V> = support.klerk
    private val pathProvider: PathProvider = support.pathProvider
    private val tableTemplate = TableTemplate<T, C, V>(klerk, kClass, support = support)

    // The page is always constructed as ModelListPage<Any, ...> from a KClass<out Any> the specification handed us,
    // so T is Any here and the cast holds. Kept on this side rather than in Specification, which cannot know it.
    @Suppress("UNCHECKED_CAST")
    private val modelViews: ModelViews<T, C>
        get() = klerk.specification.modelViews(kClass) as ModelViews<T, C>

    internal fun registerInto(route: Route) {
        with(route) {
            get(pathProvider.pathForList(kClass)) {
                respond(call)
            }

            get("${pathProvider.pathForList(kClass)}/analysis") {
                renderListAnalysis<T, V, C>(call, support, klerk, kClass)
            }
        }
    }

    // private fun <T : Any> detailsPathProvider(model: Model<T>) = "${kClass.simpleName!!}/items/${model.id}"

    // ------------ List ------------------------------------------------------

    /** Responds with the page. */
    public suspend fun respond(call: ApplicationCall) {
        val context = support.contextProvider(call, klerk)
        val modelView = modelViews
        val view = selectedView(call.request.queryParameters, modelView)

        val (table, voidEvents) = klerk.read(context) {
            Pair(
                tableTemplate.build(view, this, call),
                possibleVoidEvents(kClass),
            )
        }

        support.respondPage(call, humanName) {
            breadcrumbs(kClass, pathProvider)
            h2 { +humanName }

            div {
                apply(renderModelList2(table, voidEvents, call, modelView, context))
            }
        }
    }

    private fun selectedView(queryParameters: Parameters, modelViews: ModelViews<T, C>): ModelView<T, C> {
        val viewId = queryParameters["view"] ?: return modelViews.all
        val decoded = ViewID.parse(URLDecoder.decode(viewId, Charset.forName("utf-8")))
        return modelViews.views.single { it.id == decoded }
    }

    /*
    val refModels = list(modelView.all()) { passesFilter(it, call) }.map { ModelDisplay(it) }
            for (refModel in refModels) {
                refModel.populateRelations(this)
            }
     */

    private fun renderPieChart(reflectedModels: List<ModelDisplay<T>>): DIV.() -> Unit = {
        pre(classes = "mermaid") {
            unsafe {
                +"""pie title By state
                    ${
                    reflectedModels.groupBy { it.original.state }.map {
                        """"${it.key}" : ${it.value.size}"""
                    }.joinToString("\n")
                }
                """.trimIndent()
            }
        }
        apply(addMermaidScript())
    }

    private fun passesFilter(model: Model<T>, call: ApplicationCall): Boolean {
        val stateFilter = call.request.queryParameters["filterState"] ?: return true
        if (stateFilter == "All") {
            return true
        }
        return model.state == stateFilter
    }

    private fun renderModelList2(
        table: Table<T, C, V>,
        voidEvents: Set<VoidEvent<*, *>>,
        call: ApplicationCall,
        ModelViews: ModelViews<T, C>,
        context: C,
    ): FlowContent.() -> Unit = {
        modelTable(table)

        h3 { +"Events" }
        for (event in voidEvents) {
            p {
                with(support) {
                    eventButton(
                        event,
                        null,
                        context,
                        onCancelPath = call.request.uri,
                        onSuccessAndModelExistPath = pathProvider.pathForItem(kClass, "{id}"),
                        onErrorPath = pathProvider.base,
                    )
                }
            }
        }

        if (renderListDetails) {
            a(href = "${pathProvider.pathForList(kClass)}/analysis") { +"(More details about the list)" }
        }
    }
}

internal fun DisplayProperty.renderNameNonBreakingHtml(): HTMLTag.() -> Unit = {
    unsafe { +name().replace("-", NON_BREAKING_HYPHEN).replace(" ", NON_BREAKING_SPACE) }
}

internal fun addMermaidScript(): FlowContent.() -> Unit = {
    script(type = "module") {
        unsafe {
            +"import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';\n"
            +"mermaid.initialize({ startOnLoad: true });"
        }
    }
}

/** The list route for one model, plus its analysis route. */
public fun <T : Any, C : KlerkContext, V> Route.modelListRoutes(page: ModelListPage<T, C, V>) {
    page.registerInto(this)
}

package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.*
import dev.klerkframework.klerk.misc.ReflectedModel
import dev.klerkframework.klerk.misc.ReflectedProperty
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.web.assets.CssAsset
import io.ktor.client.request.request
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.html.*
import java.net.URLDecoder
import java.nio.charset.Charset
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties


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
    public val pathToList: String,
    public val humanName: String,
    private val renderListDetails: Boolean = false,
) {
    private val klerk: Klerk<C, V> = support.klerk
    private val pathProvider: PathProvider = support.pathProvider
    private val tableTemplate = TableTemplate<T, C, V>(klerk, kClass, support = support)

    internal fun registerInto(route: Route): Unit = with(route) {
        get(pathProvider.pathForCollection(kClass)) {
            respond(call)
        }

        get("${pathProvider.pathForCollection(kClass)}/analysis") {
            renderListAnalysis<T, V, C>(call, support, klerk, kClass)
        }
    }

    //private fun <T : Any> detailsPathProvider(model: Model<T>) = "${kClass.simpleName!!}/items/${model.id}"

    // ------------ List ------------------------------------------------------

    /** Responds with the page. */
    public suspend fun respond(call: ApplicationCall) {
        val context = support.contextProvider(call, klerk)
        val modelView = klerk.spec.getView<T>(kClass)
        val collection = getCollection(call.request.queryParameters, modelView)

        val (table, voidEvents) = klerk.read(context) {
            Pair(
                tableTemplate.build(collection, this, call),
                getPossibleVoidEvents(kClass)
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

    private fun getCollection(
        queryParameters: Parameters,
        ModelViews: ModelViews<T, C>
    ): ModelView<T, C> {
        val collectionId = queryParameters["collection"] ?: return ModelViews.all
        val decoded = CollectionId.from(URLDecoder.decode(collectionId, Charset.forName("utf-8")))
        return klerk.spec.getView<T>(kClass).getCollections().single { it.getFullId() == decoded }
    }

    /*
    val refModels = list(modelView.all()) { passesFilter(it, call) }.map { ReflectedModel(it) }
            refModels.forEach {
                apply(it.populateRelations())
            }
     */


    private fun renderPieChart(reflectedModels: List<ReflectedModel<T>>): DIV.() -> Unit = {
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
        voidEventReferences: Set<EventReference>,
        call: ApplicationCall,
        ModelViews: ModelViews<T, C>,
        context: C
    ): FlowContent.() -> Unit = {

        modelTable(table)

        h3 { +"Events" }
        voidEventReferences.forEach { event ->
            p {
                with(support) {
                    eventButton(
                        event, null, context,
                        onCancelPath = call.request.uri,
                        onSuccessAndModelExistPath = pathProvider.pathForItem(kClass, "{id}"),
                        onErrorPath = pathProvider.base,
                    )
                }
            }
        }

        if (renderListDetails) {
            a(href = "${pathProvider.pathForCollection(kClass)}/analysis") { +"(More details about the list)" }
        }
    }

}

private fun <C : KlerkContext, V> renderFilter(
    call: ApplicationCall,
    klerk: Klerk<C, V>,
    kClass: KClass<out Any>
): FlowContent.() -> Unit =
    {
        val stateNames = klerk.spec.managedModels
            .single { it.kClass == kClass }
            .stateMachine.states.filter { it !is VoidState }
            .map { it.name }

        details {
            summary { +"Filters" }
            fieldSet {
                legend { +"Filters" }
                form(action = call.request.uri, method = FormMethod.get) {

                    p {
                        label {
                            htmlFor = "collectionselect"
                            +"Collection"
                        }
                        br()
                        select {
                            id = "collectionselect"
                            name = "collection"
                            klerk.spec.getCollections().filter { it.first == kClass }.map { it.second }.forEach {
                                option {
                                    value = it.getFullId().toString()
                                    if (call.request.queryParameters["collection"]?.equals(
                                            it.getFullId().toString()
                                        ) == true
                                    ) {
                                        selected = true
                                    }
                                    +camelCaseToPretty(it.getId())
                                }
                            }
                        }
                    }

                    p {
                        label {
                            htmlFor = "filterselect"
                            +"State"
                        }
                        br()
                        select() {
                            id = "filterselect"
                            name = "filterState"
                            option {
                                value = "All"
                                if (call.request.queryParameters["filterState"]?.equals("All") == true) {
                                    selected = true
                                }
                                +"All"
                            }

                            stateNames.forEach {
                                option {
                                    value = it
                                    if (call.request.queryParameters["filterState"]?.equals(it) == true) {
                                        selected = true
                                    }
                                    +it
                                }
                            }
                        }
                    }

                    p {
                        label {
                            htmlFor = "filterstring"
                            title = additionalFiltersExplanationText
                            +"Additional filters"

                            /*                    details {
                                            summary { +"Filter" }
                                            +"This field understands a few filter commands. Some examples:"
                                            ul {
                                                li { +"created>2023-03-10T20:23:13Z" }
                                                li { +"updated<2023-03-10T20:23:13Z" }
                                                li { +"contains=Bertil" }
                                                li { +"updated<2023-03-10T20:23:13Z contains=Anna" }
                                            }
                                        }

                     */
                        }
                        br()
                        textInput {
                            id = "filterstring"
                            name = "filterString"
                            size = "40"
                            title = additionalFiltersExplanationText
                            value = call.request.queryParameters["filterString"] ?: ""
                        }
                    }

                    p {
                        button(type = ButtonType.submit) { +"Apply filter" }
                    }

                }
            }
        }

    }


private fun <T : Any, C : KlerkContext, V> renderTable(
    models: List<Model<T>>,
    support: WebSupport<C, V>,
    kClass: KClass<out Any>,
    columns: List<Column<T>>,
): FlowContent.() -> Unit = {
    fun classesFor(element: String, model: Model<*>? = null) =
        support.classProvider.attr(UiPart.ModelTable, element, model = model)

    table(classes = classesFor("table")) {
        thead(classesFor("thead")) {
            tr(classesFor("tr")) {
                columns.forEach { column -> th { +column.header } }
            }
        }
        tbody(classesFor("tbody")) {
            models.forEach { model ->
                val path = support.pathProvider.pathForItem(kClass, model.id)
                tr(classesFor("tr", model)) {
                    if (path != null) {
                        onClick = """window.location = '$path';"""
                    }
                    columns.forEach { column ->
                        td(classesFor("td", model)) { column.cell(this, model) }
                    }
                }
            }
        }
    }
}


internal fun ReflectedProperty.renderValueNonBreakingHtml(): HTMLTag.() -> Unit = {
    val temporal = renderTemporalContainer(value)
    if (temporal != null) {
        unsafe { +temporal.replace("-", NON_BREAKING_HYPHEN) }
    } else if (value is Instant) {
        unsafe {
            +(dateFormatter.format((value as Instant).toLocalDateTime(TimeZone.currentSystemDefault()))
                .replace("-", NON_BREAKING_HYPHEN))
        }
    } else {
        +value.toString()
    }
}

internal fun ReflectedProperty.renderNameNonBreakingHtml(): HTMLTag.() -> Unit = {
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

/** A `?cursor=` that isn't one (hand-edited, or kept from an older version of Klerk) starts from the beginning. */
private fun createQueryOptions(queryParameters: Parameters, pageSize: Int): QueryOptions {
    val cursor = queryParameters["cursor"]?.let {
        try {
            QueryListCursor.fromString(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    return QueryOptions(maxItems = pageSize, cursor = cursor, countTotal = true)
}

private fun <T : Any> createMetaFilter(queryParameters: Parameters): ((Model<T>) -> Boolean)? {
    val includeState = queryParameters["filterState"]
    val filterString = queryParameters["filterString"] ?: ""
    if ((includeState == null || includeState == "All") && filterString.isEmpty()) {
        return null
    }
    return fun(model: Model<T>): Boolean {
        if (includeState != null && includeState != model.state && includeState != "All") {
            return false
        }
        require(!filterString.contains("  ")) { "Filter string cannot contain two consecutive spaces" }
        val filtersAndValues = filterString.split(" ")

        // should improve this...
        filtersAndValues.forEach {
            if (it.startsWith("created>")) {
                val timeString = it.split(">").last()
                val instant = Instant.parse(timeString)
                if (model.createdAt < instant) {
                    return false
                }
            }
            if (it.startsWith("created<")) {
                val timeString = it.split("<").last()
                val instant = Instant.parse(timeString)
                if (model.createdAt > instant) {
                    return false
                }
            }
            if (it.startsWith("updated>")) {
                val timeString = it.split(">").last()
                val instant = Instant.parse(timeString)
                if (model.lastModifiedAt < instant) {
                    return false
                }
            }
            if (it.startsWith("updated<")) {
                val timeString = it.split("<").last()
                val instant = Instant.parse(timeString)
                if (model.lastModifiedAt > instant) {
                    return false
                }
            }
            if (it.startsWith("contains=")) {
                val textToFind = it.split("=").last()
                if (model.props::class.declaredMemberProperties.none { prop ->
                        prop.getter.call(model.props).toString().contains(textToFind)
                    }) {
                    return false
                }
            }
        }

        return true
    }
}

private const val BUTTON_STYLE = "margin: 1rem;"

private fun <T : Any> renderPagination(
    queryResponse: QueryResponse<T>,
    call: ApplicationCall
): FlowContent.() -> Unit = {
    div {

        // Each cursor is null when there is no such page, so no button here is ever a dead link.
        listOf(
            "First" to queryResponse.cursorFirstPage,
            "Previous" to queryResponse.cursorPreviousPage,
            "Next" to queryResponse.cursorNextPage,
            "Last" to queryResponse.cursorLastPage,
        ).forEach { (label, cursor) ->
            cursor?.let {
                a(withQueryParam(call.request.uri, "cursor", it.toString())) {
                    button {
                        style = BUTTON_STYLE
                        +label
                    }
                }
            }
        }
    }
}

/** [url] with [paramName] set to [paramValue], replacing it if it is already there and keeping every other parameter. */
internal fun withQueryParam(url: String, paramName: String, paramValue: String): String {
    val path = url.substringBefore('?')
    val parameters = ParametersBuilder().apply {
        appendAll(parseQueryString(url.substringAfter('?', "")))
        set(paramName, paramValue)
    }.build()
    return "$path?${parameters.formUrlEncode()}"
}

/**
 * One column of a [TableTemplate]. A column is a value, so adding one is `columns + Column(...)` rather than a
 * setting on the table.
 *
 * @param header the text in the `<th>`.
 * @param cell renders the `<td>` contents for one model.
 */
public class Column<T : Any>(
    public val header: String,
    public val cell: TD.(Model<T>) -> Unit,
) {
    public companion object {
        /** Created, Updated, State and a summary of the properties. */
        public fun <T : Any> defaults(): List<Column<T>> = listOf(
            Column("Created") { model ->
                unsafe {
                    +dateFormatter.format(model.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()))
                        .replace("-", NON_BREAKING_HYPHEN)
                }
            },
            Column("Updated") { model ->
                unsafe {
                    +dateFormatter.format(model.lastModifiedAt.toLocalDateTime(TimeZone.currentSystemDefault()))
                        .replace("-", NON_BREAKING_HYPHEN)
                }
            },
            Column("State") { model -> +model.state },
            Column("Properties") { model ->
                val text = model.props.toString()
                +(if (text.length < 100) text else text.take(100).plus("..."))
            },
        )
    }
}

/**
 * A paginated, filterable table of the models in a collection. Rows the current actor is not authorized to read are
 * left out of the page rather than raising an error.
 *
 * @param columns what to show. Start from [Column.defaults] and map over it to change, add or drop a column.
 */
public class TableTemplate<T : Any, C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>,
    private val support: WebSupport<C, V>,
    private val columns: List<Column<T>> = Column.defaults(),
    private val pageSize: Int = 30,
) {

    public fun build(
        source: ModelView<T, C>,
        reader: Reader<C, V>,
        call: ApplicationCall,
    ): Table<T, C, V> {
        val queryOptions = createQueryOptions(call.request.queryParameters, pageSize)
        val metaFilter = createMetaFilter<T>(call.request.queryParameters)
        // queryIfAuthorized so a row the actor may not read shrinks the page instead of turning it into a 500.
        val queryResponse = with(reader) { source.filter(filter = metaFilter).queryIfAuthorized(queryOptions) }
        return Table(queryResponse, support, call, klerk, kClass, columns)
    }

}

/** A built table, ready to be rendered. Produced by [TableTemplate.build]. */
public class Table<T : Any, C : KlerkContext, V>(
    private val queryResponse: QueryResponse<T>,
    private val support: WebSupport<C, V>,
    private val call: ApplicationCall,
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>,
    private val columns: List<Column<T>>,
) {
    internal fun content(): FlowContent.() -> Unit = {
        apply(renderFilter(call, klerk, kClass))
        if (queryResponse.items.isEmpty()) {
            p { +"The list is empty" }
        } else {
            apply(renderTable(queryResponse.items, support, kClass, columns))
        }
        // Outside the branch: a page can be empty and still have pages around it, and dropping the links there would
        // leave the reader stuck.
        if (queryResponse.hasPreviousPage || queryResponse.hasNextPage) {
            apply(renderPagination(queryResponse, call))
        }
    }
}

/** Renders a built [Table]: the filter controls, the table itself and the pagination links. */
public fun <T : Any, C : KlerkContext, V> FlowContent.modelTable(table: Table<T, C, V>) {
    apply(table.content())
}

private val additionalFiltersExplanationText: String = """This field understands a few filter commands. Some examples:
                        |created>2023-03-10T20:23:13Z
                        |updated<2023-03-10T20:23:13Z
                        |contains=Bertil
                        |updated<2023-03-10T20:23:13Z contains=Anna
                    """.trimMargin()

/** The list route for one model, plus its analysis route. */
public fun <T : Any, C : KlerkContext, V> Route.modelListRoutes(page: ModelListPage<T, C, V>): Unit =
    page.registerInto(this)

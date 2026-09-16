package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.camelCaseToPretty
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.klerk.view.*
import io.ktor.client.request.request
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

/**
 * One column of a [TableTemplate]. A column is a value, so adding one is `columns + Column(...)` rather than a
 * setting on the table.
 */
public class Column<T : Any>(
    /** The text in the `<th>`. */
    public val header: String,
    /** Renders the `<td>` contents for one model. */
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
 * [columns] is what to show. Start from [Column.defaults] and map over it to change, add or drop a column.
 */
public class TableTemplate<T : Any, C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>,
    private val support: WebSupport<C, V>,
    private val columns: List<Column<T>> = Column.defaults(),
    private val pageSize: Int = 30,
) {

    /** Builds one page of [source] for a request. Render it with `modelTable`. */
    public fun build(
        source: ModelView<T, C>,
        reader: ModelReader<C, V>,
        call: ApplicationCall,
    ): Table<T, C, V> {
        val queryOptions = createQueryOptions(call.request.queryParameters, pageSize)
        val metaFilter = createMetaFilter<T>(call.request.queryParameters)
        // query() skips a row the actor may not read instead of turning the page into a 500.
        val queryResponse = with(reader) { (metaFilter?.let { source.filter(it) } ?: source).query(queryOptions) }
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

private fun <C : KlerkContext, V> renderFilter(
    call: ApplicationCall,
    klerk: Klerk<C, V>,
    kClass: KClass<out Any>,
): FlowContent.() -> Unit =
    {
        val stateNames = klerk.specification.managedModels
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
                            htmlFor = "viewselect"
                            +"View"
                        }
                        br()
                        select {
                            id = "viewselect"
                            name = "view"
                            klerk.specification.registeredViews
                                .filter { it.modelClass == kClass }
                                .map { it.view }
                                .forEach {
                                option {
                                    value = it.id.toString()
                                    if (call.request.queryParameters["view"]?.equals(
                                            it.id.toString(),
                                        ) == true
                                    ) {
                                        selected = true
                                    }
                                    +camelCaseToPretty(it.id.shortId)
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

                            for (stateName in stateNames) {
                                option {
                                    value = stateName
                                    if (call.request.queryParameters["filterState"]?.equals(stateName) == true) {
                                        selected = true
                                    }
                                    +stateName
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
                for (column in columns) {
                    th { +column.header }
                }
            }
        }
        tbody(classesFor("tbody")) {
            for (model in models) {
                val path = support.pathProvider.pathForItem(kClass, model.id)
                tr(classesFor("tr", model)) {
                    if (path != null) {
                        onClick = """window.location = '$path';"""
                    }
                    for (column in columns) {
                        td(classesFor("td", model)) { column.cell(this, model) }
                    }
                }
            }
        }
    }
}

internal fun DisplayProperty.renderValueNonBreakingHtml(): HTMLTag.() -> Unit = {
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

/** A `?cursor=` that isn't one (hand-edited, or kept from an older version of Klerk) starts from the beginning. */
private fun createQueryOptions(queryParameters: Parameters, pageSize: Int): QueryOptions {
    val cursor = queryParameters["cursor"]?.let {
        try {
            QueryListCursor.parse(it)
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
        for (filter in filtersAndValues) {
            if (filter.startsWith("created>")) {
                val timeString = filter.split(">").last()
                val instant = Instant.parse(timeString)
                if (model.createdAt < instant) {
                    return false
                }
            }
            if (filter.startsWith("created<")) {
                val timeString = filter.split("<").last()
                val instant = Instant.parse(timeString)
                if (model.createdAt > instant) {
                    return false
                }
            }
            if (filter.startsWith("updated>")) {
                val timeString = filter.split(">").last()
                val instant = Instant.parse(timeString)
                if (model.lastModifiedAt < instant) {
                    return false
                }
            }
            if (filter.startsWith("updated<")) {
                val timeString = filter.split("<").last()
                val instant = Instant.parse(timeString)
                if (model.lastModifiedAt > instant) {
                    return false
                }
            }
            if (filter.startsWith("contains=")) {
                val textToFind = filter.split("=").last()
                if (ObjectSchema.of(model.props::class).fields.none { field ->
                        field.get(model.props).toString().contains(textToFind)
                    }) {
                    return false
                }
            }
        }

        return true
    }
}

private fun <T : Any> renderPagination(
    queryResponse: QueryResponse<T>,
    call: ApplicationCall,
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

/**
 * [url] with [paramName] set to [paramValue], replacing it if it is already there and keeping every other parameter.
 */
internal fun withQueryParam(url: String, paramName: String, paramValue: String): String {
    val path = url.substringBefore('?')
    val parameters = ParametersBuilder().apply {
        appendAll(parseQueryString(url.substringAfter('?', "")))
        set(paramName, paramValue)
    }.build()
    return "$path?${parameters.formUrlEncode()}"
}

private val additionalFiltersExplanationText: String = """This field understands a few filter commands. Some examples:
                        |created>2023-03-10T20:23:13Z
                        |updated<2023-03-10T20:23:13Z
                        |contains=Bertil
                        |updated<2023-03-10T20:23:13Z contains=Anna
                    """.trimMargin()

private const val BUTTON_STYLE = "margin: 1rem;"

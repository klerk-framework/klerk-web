package dev.klerkframework.web
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.camelCaseToPretty
import dev.klerkframework.klerk.read.ModelReader
import dev.klerkframework.klerk.statemachine.VoidState
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse
import dev.klerkframework.klerk.view.query
import dev.klerkframework.web.assets.listJs
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import kotlin.reflect.KClass
import kotlin.time.Instant

/** The order of a [TableTemplate] when the request does not pick one. [key] is a [Column.key]. */
public class TableSort(public val key: String, public val ascending: Boolean = true)

/** Where a [TableTemplate] puts its sort and filter controls. */
public enum class TableControls {
    /** A Filters panel above the table. Clicking a header cycles its sort order. */
    Panel,

    /**
     * By each header: an arrow up and an arrow down that sort the column, and a funnel that opens a dialog with the
     * column's filter. Experimental.
     */
    Header,
}

/**
 * A paginated table of the models in a collection, which the reader can sort and filter on its columns. Rows the
 * current actor is not authorized to read are left out of the page rather than raising an error.
 *
 * [columns] is what to show. Start from [Column.defaults] and map over it to change, add or drop a column.
 * [defaultSort] is the order when the request does not pick one; without it, the view's own order is used.
 * [paramPrefix] is put in front of every query parameter the table uses, so that two tables can share a page.
 * [controls] says where the sort and filter controls are.
 *
 * @throws IllegalArgumentException if two columns have the same key, or [defaultSort] names no sortable column
 */
public class TableTemplate<T : Any, C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>,
    private val support: WebSupport<C, V>,
    columns: List<Column<T>> = Column.defaults(),
    private val pageSize: Int = 30,
    private val defaultSort: TableSort? = null,
    paramPrefix: String = "",
    private val controls: TableControls = TableControls.Panel,
) {
    private val names = TableParameterNames(paramPrefix)

    private val columns: List<Column<T>> = columns.map { column ->
        if (column.kind == ColumnKind.State) column.withKind(ColumnKind.Choice(stateNames())) else column
    }

    init {
        val duplicates = this.columns.mapNotNull { it.key }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Columns must have unique keys, but these are repeated: $duplicates" }
        defaultSort?.let { sort ->
            require(this.columns.any { it.key == sort.key && it.isSortable }) {
                "defaultSort names '${sort.key}', which is not the key of a sortable column"
            }
        }
    }

    private fun stateNames(): List<String> = klerk.specification.managedModels
        .single { it.kClass == kClass }
        .stateMachine.states.filter { it !is VoidState }
        .map { it.name }

    /**
     * Builds one page of [source] for a request, sorted and filtered as its query parameters say. Render it with
     * `modelTable`.
     *
     * If a parameter is invalid, the response status is set to 400 and the table renders the problem and a link to
     * the same page without the invalid parameters.
     */
    public fun build(source: ModelView<T, C>, reader: ModelReader<C, V>, call: ApplicationCall): Table<T, C, V> {
        val parameters = call.request.queryParameters
        val request = parseTableRequest(parameters, columns, names, defaultSort)
        if (request.problems.isNotEmpty()) {
            call.response.status(HttpStatusCode.BadRequest)
            return Table(null, request, support, call, klerk, kClass, columns, names, controls)
        }
        val filter = request.filters.takeIf { it.isNotEmpty() }?.let { filters ->
            { model: Model<T> -> filters.all { it.matches(model) } }
        }
        val options = QueryOptions(maxItems = pageSize, cursor = request.cursor, countTotal = true)
        // query() skips a row the actor may not read instead of turning the page into a 500.
        val queryResponse = with(reader) { source.query(options, sort = request.sort?.comparator(), filter = filter) }
        return Table(queryResponse, request, support, call, klerk, kClass, columns, names, controls)
    }
}

/** Values first, in the requested direction, then empty values, then values the actor may not read. */
private fun <T : Any> ActiveSort<T>.comparator(): Comparator<Model<T>> {
    val selector = column.value!!
    val kind = column.kind!!
    return Comparator { a, b ->
        val valueA = selector(a)
        val valueB = selector(b)
        val rank = rank(valueA).compareTo(rank(valueB))
        if (rank != 0 || valueA == null || valueA === Unreadable) {
            rank
        } else {
            val order = kind.sortKey(valueA).compareTo(kind.sortKey(valueB!!))
            if (ascending) order else -order
        }
    }
}

private fun rank(value: Any?): Int = when {
    value === Unreadable -> 2
    value == null -> 1
    else -> 0
}

/** A built table, ready to be rendered. Produced by [TableTemplate.build]. */
public class Table<T : Any, C : KlerkContext, V> internal constructor(
    private val queryResponse: QueryResponse<T>?,
    private val request: TableRequest<T>,
    private val support: WebSupport<C, V>,
    private val call: ApplicationCall,
    private val klerk: Klerk<C, V>,
    private val kClass: KClass<out Any>,
    private val columns: List<Column<T>>,
    private val names: TableParameterNames,
    private val controls: TableControls,
) {
    /**
     * 400 if the request had invalid table parameters, otherwise 200. [TableTemplate.build] has already set it on the
     * call; pass it on if you respond in a way that sets a status of its own.
     */
    public val status: HttpStatusCode =
        if (request.problems.isEmpty()) HttpStatusCode.OK else HttpStatusCode.BadRequest

    internal fun content(): FlowContent.() -> Unit = {
        if (queryResponse == null) {
            apply(renderProblems(request.problems, call.request.uri))
        } else {
            val views = klerk.specification.registeredViews.filter { it.modelClass == kClass }.map { it.view }
            when (controls) {
                TableControls.Panel -> apply(renderFilter(call, views, columns, request, names))
                TableControls.Header -> if (views.isNotEmpty()) apply(renderViewLinks(call, views, names))
            }
            apply(renderActiveFilters(call, request, names))
            if (queryResponse.items.isEmpty()) {
                p { +"The list is empty" }
            } else {
                apply(renderTable(queryResponse.items, support, kClass, columns, request, names, call, controls))
            }
            // Outside the branch: a page can be empty and still have pages around it, and dropping the links there
            // would leave the reader stuck.
            if (queryResponse.hasPreviousPage || queryResponse.hasNextPage) {
                apply(renderPagination(queryResponse, call, names))
            }
        }
    }
}

/** Renders a built [Table]: the filter controls, the table itself and the pagination links. */
public fun <T : Any, C : KlerkContext, V> FlowContent.modelTable(table: Table<T, C, V>) {
    apply(table.content())
}

internal fun renderProblems(problems: List<ParameterProblem>, uri: String): FlowContent.() -> Unit = {
    val drop = problems.flatMap { it.drop }.toSet()
    p { +"The list cannot be shown:" }
    ul {
        for (problem in problems) {
            li { +problem.message }
        }
    }
    p { a(editQuery(uri) { names -> names.filter { it in drop } }) { +"Show the list without them" } }
}

/** Hidden inputs that carry over the parameters a form does not set itself, e.g. the sort order. */
private fun FORM.carryOver(parameters: Parameters, setByForm: (String) -> Boolean) {
    parameters.forEach { name, values ->
        if (!setByForm(name)) {
            values.forEach { hiddenInput(name = name) { value = it } }
        }
    }
}

private fun FlowContent.viewSelect(views: List<ModelView<*, *>>, parameters: Parameters) {
    p {
        label {
            htmlFor = "viewselect"
            +"View"
        }
        br()
        select {
            id = "viewselect"
            name = VIEW_PARAMETER
            views.forEach {
                option {
                    value = it.id.toString()
                    selected = parameters[VIEW_PARAMETER] == it.id.toString()
                    +camelCaseToPretty(it.id.shortId)
                }
            }
        }
    }
}

private fun <T : Any> renderFilter(
    call: ApplicationCall,
    views: List<ModelView<*, *>>,
    columns: List<Column<T>>,
    request: TableRequest<T>,
    names: TableParameterNames,
): FlowContent.() -> Unit = {
    val parameters = call.request.queryParameters

    details {
        if (request.filters.isNotEmpty()) {
            open = true
        }
        summary { +"Filters" }
        form(action = call.request.path(), method = FormMethod.get) {
            carryOver(parameters) { it == names.cursor || it == VIEW_PARAMETER || names.isFilter(it) }
            if (views.isNotEmpty()) {
                viewSelect(views, parameters)
            }
            for (column in columns.filter { it.isFilterable }) {
                apply(renderColumnFilter(column, parameters, names))
            }
            p {
                button(type = ButtonType.submit) { +"Apply filter" }
            }
        }
    }
}

/** The views as a row of links. The current one, or `all` when none is picked, is marked instead of linked. */
private fun renderViewLinks(
    call: ApplicationCall,
    views: List<ModelView<*, *>>,
    names: TableParameterNames,
): FlowContent.() -> Unit = {
    val current = call.request.queryParameters[VIEW_PARAMETER]?.takeIf { it.isNotBlank() }
        ?: views.firstOrNull { it.id.shortId == "all" }?.id?.toString()
    nav {
        attributes["aria-label"] = "View"
        p {
            +"View: "
            views.forEachIndexed { index, view ->
                if (index > 0) {
                    +" · "
                }
                val id = view.id.toString()
                val name = camelCaseToPretty(view.id.shortId)
                if (id == current) {
                    strong {
                        attributes["aria-current"] = "page"
                        +name
                    }
                } else {
                    a(
                        editQuery(call.request.uri, add = { append(VIEW_PARAMETER, id) }) {
                            it.filter { param -> param == VIEW_PARAMETER || param == names.cursor }
                        },
                    ) { +name }
                }
            }
        }
    }
}
private fun <T : Any> renderActiveFilters(
    call: ApplicationCall,
    request: TableRequest<T>,
    names: TableParameterNames,
): FlowContent.() -> Unit = {
    if (request.filters.isNotEmpty()) {
        p {
            for (filter in request.filters) {
                val key = filter.column.key!!
                span {
                    +"${filter.column.header}: ${describe(filter)} "
                    val without = editQuery(call.request.uri) {
                        it.filter { name -> names.isFilterOf(name, key) || name == names.cursor }
                    }
                    a(without) {
                        title = "Remove the filter"
                        +"✕"
                    }
                }
                +" "
            }
            a(editQuery(call.request.uri) { it.filter { name -> names.isFilter(name) || name == names.cursor } }) {
                +"Clear all filters"
            }
        }
    }
}

private fun <T : Any> describe(filter: ColumnFilter<T>): String = buildList {
    if (filter.anyOf.isNotEmpty()) {
        add(filter.anyOf.joinToString(" or ") { it?.toString() ?: "empty" })
    }
    filter.min?.let { add("≥ ${describeBound(it)}") }
    filter.max?.let { add("≤ ${describeBound(it)}") }
    filter.contains?.let { add("contains '$it'") }
}.joinToString(", ")

private fun describeBound(value: Any): String = when (value) {
    is Instant -> formatTime(value)
    else -> value.toString()
}

private fun <T : Any> renderColumnFilter(
    column: Column<T>,
    parameters: Parameters,
    names: TableParameterNames,
): FlowContent.() -> Unit = {
    val key = column.key!!
    val kind = column.kind!!
    val anyOfName = names.anyOf(key)
    val tokens = parameters.getAll(anyOfName).orEmpty()
        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    val inputId = "${anyOfName.replace('.', '-')}-"

    fieldSet {
        legend { +column.header }
        if (kind is ColumnKind.Choice) {
            for (option in kind.options) {
                label {
                    checkBoxInput(name = anyOfName) {
                        value = option
                        checked = option in tokens
                    }
                    +" $option "
                }
            }
        } else if (kind.hasAnyOf) {
            label {
                htmlFor = "${inputId}any"
                +"Any of (comma-separated) "
            }
            textInput(name = anyOfName) {
                id = "${inputId}any"
                value = tokens.filter { !(it == NULL_TOKEN && column.nullable) }.joinToString(", ")
            }
            br()
        }
        if (column.nullable) {
            label {
                checkBoxInput(name = anyOfName) {
                    value = NULL_TOKEN
                    checked = NULL_TOKEN in tokens
                }
                +" Empty"
            }
            br()
        }
        if (kind.hasRange) {
            for ((label, name) in listOf("From" to names.min(key), "To" to names.max(key))) {
                label {
                    htmlFor = "$inputId$label"
                    +"$label${if (kind is ColumnKind.DurationKind) " (seconds)" else ""} "
                }
                input(type = rangeInputType(kind), name = name) {
                    id = "$inputId$label"
                    if (kind is ColumnKind.Numeric || kind is ColumnKind.DurationKind) {
                        attributes["step"] = "any"
                    }
                    value = parameters[name] ?: ""
                }
                +" "
            }
            br()
        }
        if (kind.hasContains) {
            label {
                htmlFor = "${inputId}contains"
                +"Contains "
            }
            textInput(name = names.contains(key)) {
                id = "${inputId}contains"
                value = parameters[names.contains(key)] ?: ""
            }
        }
    }
}

private fun rangeInputType(kind: ColumnKind): InputType = when (kind) {
    is ColumnKind.InstantKind -> InputType.dateTimeLocal
    is ColumnKind.DateKind -> InputType.date
    else -> InputType.number
}

private fun <T : Any, C : KlerkContext, V> renderTable(
    models: List<Model<T>>,
    support: WebSupport<C, V>,
    kClass: KClass<out Any>,
    columns: List<Column<T>>,
    request: TableRequest<T>,
    names: TableParameterNames,
    call: ApplicationCall,
    controls: TableControls,
): FlowContent.() -> Unit = {
    fun classesFor(element: String, model: Model<*>? = null, property: String? = null) =
        support.classProvider.attr(UiPart.ModelTable, element, property = property, model = model)

    val uri = call.request.uri
    val filtered = request.filters.map { it.column }.toSet()

    table(classes = classesFor("table")) {
        attributes["data-klerk-list"] = ""
        thead(classesFor("thead")) {
            tr(classesFor("tr")) {
                for (column in columns) {
                    th(classes = classesFor("th", property = column.key)) {
                        if (controls == TableControls.Header) {
                            headerControls(column, request, names, call)
                        } else if (column.isSortable) {
                            val sort = request.sort?.takeIf { it.column == column }
                            a(sortUrl(uri, column, sort, names)) {
                                +column.header
                                if (sort != null) {
                                    +(if (sort.ascending) " ▲" else " ▼")
                                }
                            }
                        } else {
                            +column.header
                        }
                        if (controls == TableControls.Panel && column in filtered) {
                            span {
                                title = "Filtered"
                                +" ⧩"
                            }
                        }
                    }
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
                    var rowLinkRendered = false
                    for (column in columns) {
                        td(classesFor("td", model, column.key)) {
                            val text = column.text
                            if (text != null && path != null) {
                                a(path) {
                                    // One Tab stop per row: the other cells link to the same page.
                                    if (rowLinkRendered) {
                                        attributes["tabindex"] = "-1"
                                    } else {
                                        attributes["data-klerk-row-link"] = ""
                                        rowLinkRendered = true
                                    }
                                    +text(model)
                                }
                            } else {
                                column.cell(this, model)
                            }
                        }
                    }
                }
            }
        }
    }
    script {
        src = support.pathProvider.assetPath(listJs.getPathAndHash())
        defer = true
    }
}

/**
 * The sort arrows and the filter button, then the header text and the filter dialog. Active controls are not dimmed.
 */
private fun <T : Any> TH.headerControls(
    column: Column<T>,
    request: TableRequest<T>,
    names: TableParameterNames,
    call: ApplicationCall,
) {
    val uri = call.request.uri
    val sort = request.sort?.takeIf { it.column == column }
    val filter = request.filters.firstOrNull { it.column == column }
    // The popover stays outside this span, so that CSS hiding the controls does not hide an open dialog.
    span {
        attributes["data-table-controls"] = ""
        style = "display: block; white-space: nowrap;"
        if (column.isSortable) {
            for (ascending in listOf(true, false)) {
                val active = sort?.ascending == ascending
                // Clicking the active arrow removes the sort.
                val href = editQuery(uri, add = {
                    if (!active) {
                        append(names.sort, column.key!!)
                        if (!ascending) append(names.direction, "desc")
                    }
                }) { it.filter { name -> name == names.sort || name == names.direction || name == names.cursor } }
                +" "
                a(href) {
                    title = if (ascending) "Sort ascending" else "Sort descending"
                    style = controlStyle(active)
                    if (active) attributes["data-active"] = ""
                    +when {
                        ascending && active -> "↑"
                        ascending -> "⇡"
                        active -> "↓"
                        else -> "⇣"
                    }
                }
            }
        }
        if (column.isFilterable) {
            +" "
            button(type = ButtonType.button) {
                title = if (filter == null) "Filter" else "Filtered: ${describe(filter)}"
                attributes["popovertarget"] = names.filterDialog(column.key!!)
                style = "background: none; border: none; padding: 0; margin: 0; color: inherit; cursor: pointer; " +
                    controlStyle(filter != null)
                if (filter != null) attributes["data-active"] = ""
                unsafe { +FUNNEL_SVG }
            }
        }
    }
    +column.header
    if (column.isFilterable) {
        val key = column.key!!
        val dialogId = names.filterDialog(key)
        // TH does not offer div, although a popover in a header cell is valid HTML.
        DIV(attributesMapOf("id", dialogId, "popover", ""), consumer).visit {
            form(action = call.request.path(), method = FormMethod.get) {
                carryOver(call.request.queryParameters) { it == names.cursor || names.isFilterOf(it, key) }
                apply(renderColumnFilter(column, call.request.queryParameters, names))
                p {
                    button(type = ButtonType.submit) { +"Apply" }
                    +" "
                    button(type = ButtonType.button) {
                        attributes["popovertarget"] = dialogId
                        attributes["popovertargetaction"] = "hide"
                        +"Cancel"
                    }
                    if (filter != null) {
                        +" "
                        a(
                            editQuery(uri) {
                                it.filter { name -> names.isFilterOf(name, key) || name == names.cursor }
                            },
                        ) {
                            +"Remove filter"
                        }
                    }
                }
            }
        }
    }
}

private fun controlStyle(active: Boolean): String = "text-decoration: none;" + if (active) "" else " opacity: 0.35;"

private const val FUNNEL_SVG = """<svg viewBox="0 0 16 16" width="0.9em" height="0.9em" fill="currentColor" """ +
    """aria-hidden="true"><path d="M1 2h14l-5.5 6.5V14l-3-1.5V8.5z"/></svg>"""

/** Unsorted → ascending → descending → unsorted. */
private fun <T : Any> sortUrl(
    uri: String,
    column: Column<T>,
    current: ActiveSort<T>?,
    names: TableParameterNames,
): String {
    val dropped = setOf(names.sort, names.direction, names.cursor)
    return editQuery(uri, add = {
        when {
            current == null -> append(names.sort, column.key!!)
            current.ascending -> {
                append(names.sort, column.key!!)
                append(names.direction, "desc")
            }
            else -> {}
        }
    }) { it.filter { name -> name in dropped } }
}

internal fun DisplayProperty.renderValueNonBreakingHtml(): HTMLTag.() -> Unit = {
    val temporal = renderTemporalContainer(value)
    if (temporal != null) {
        unsafe { +temporal.replace("-", NON_BREAKING_HYPHEN) }
    } else if (value is Instant) {
        unsafe {
            +(
                dateFormatter.format((value as Instant).toLocalDateTime(TimeZone.currentSystemDefault()))
                    .replace("-", NON_BREAKING_HYPHEN)
                )
        }
    } else {
        +value.toString()
    }
}

private fun <T : Any> renderPagination(
    queryResponse: QueryResponse<T>,
    call: ApplicationCall,
    names: TableParameterNames,
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
                a(withQueryParam(call.request.uri, names.cursor, it.toString())) {
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
internal fun withQueryParam(url: String, paramName: String, paramValue: String): String =
    editQuery(url, add = { append(paramName, paramValue) }) { it.filter { name -> name == paramName } }

/** [url] without the parameters [remove] picks from its parameter names, and with those [add] appends. */
private fun editQuery(
    url: String,
    add: ParametersBuilder.() -> Unit = {},
    remove: (Set<String>) -> Collection<String>,
): String {
    val path = url.substringBefore('?')
    val existing = parseQueryString(url.substringAfter('?', ""))
    val removed = remove(existing.names()).toSet()
    val parameters = ParametersBuilder().apply {
        existing.forEach { name, values -> if (name !in removed) appendAll(name, values) }
        add()
    }.build()
    return if (parameters.isEmpty()) path else "$path?${parameters.formUrlEncode()}"
}

internal const val VIEW_PARAMETER = "view"

private const val BUTTON_STYLE = "margin: 1rem;"

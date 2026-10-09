package dev.klerkframework.web

import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.view.QueryListCursor
import io.ktor.http.Parameters

/** The query parameter names of one table, all starting with the table's prefix. */
internal class TableParameterNames(private val prefix: String) {
    val sort = "${prefix}sort"
    val direction = "${prefix}dir"
    val cursor = "${prefix}cursor"
    val filterPrefix = "${prefix}f."

    /** The id of the filter dialog of a column. */
    fun filterDialog(key: String) = "${prefix}filter-$key"

    fun anyOf(key: String) = "$filterPrefix$key"
    fun min(key: String) = "$filterPrefix$key.min"
    fun max(key: String) = "$filterPrefix$key.max"
    fun contains(key: String) = "$filterPrefix$key.contains"

    fun isFilter(name: String) = name.startsWith(filterPrefix)
    fun isFilterOf(name: String, key: String) = name == anyOf(key) || name.startsWith("${anyOf(key)}.")
}

/** An invalid query parameter, and the parameters to drop to get a valid request. */
internal class ParameterProblem(val message: String, val drop: Set<String>)

internal class ActiveSort<T : Any>(val column: Column<T>, val ascending: Boolean)

/** What one column is filtered on. A model matches if it matches any of the given conditions. */
internal class ColumnFilter<T : Any>(
    val column: Column<T>,
    /** Values in the form [ColumnKind.matchKey] gives; null for "empty". */
    val anyOf: Set<Any?>,
    val min: Any?,
    val max: Any?,
    val contains: String?,
) {
    private val kind = column.kind!!
    private val hasCondition = min != null || max != null || contains != null

    fun matches(model: Model<T>): Boolean {
        val value = column.value!!(model)
        if (value === Unreadable) {
            return false
        }
        if (anyOf.isNotEmpty() && (value?.let { kind.matchKey(it) }) in anyOf) {
            return true
        }
        if (!hasCondition || value == null) {
            return false
        }
        return (min == null || compare(value, min) >= 0) &&
            (max == null || compare(value, max) <= 0) &&
            (contains == null || value.toString().contains(contains, ignoreCase = true))
    }

    private fun compare(value: Any, bound: Any): Int = kind.sortKey(value).compareTo(bound)
}

internal class TableRequest<T : Any>(
    val sort: ActiveSort<T>?,
    val filters: List<ColumnFilter<T>>,
    val cursor: QueryListCursor?,
    val problems: List<ParameterProblem>,
)

/** The value of a single-valued parameter, or null if it is missing or blank. */
private fun Parameters.single(name: String): String? = getAll(name)?.lastOrNull { it.isNotBlank() }?.trim()

internal fun <T : Any> parseTableRequest(
    parameters: Parameters,
    columns: List<Column<T>>,
    names: TableParameterNames,
    defaultSort: TableSort?,
): TableRequest<T> {
    val problems = mutableListOf<ParameterProblem>()
    val byKey = columns.filter { it.key != null }.associateBy { it.key!! }

    fun problem(message: String, vararg drop: String) {
        problems.add(ParameterProblem(message, setOf(*drop, names.cursor)))
    }

    val filters = mutableListOf<ColumnFilter<T>>()
    val filterNames = parameters.names().filter { names.isFilter(it) }
    val filteredKeys = filterNames.map { it.removePrefix(names.filterPrefix).substringBefore('.') }.distinct()
    for (key in filteredKeys) {
        val column = byKey[key]
        val ownNames = filterNames.filter { names.isFilterOf(it, key) }.toTypedArray()
        if (column == null) {
            problem("There is no column '$key' to filter on", *ownNames)
            continue
        }
        if (!column.isFilterable) {
            problem("The column ${column.header} cannot be filtered", *ownNames)
            continue
        }
        filters.add(parseFilter(parameters, column, ownNames.toList(), names, ::problem) ?: continue)
    }

    val sort = parameters.single(names.sort)?.let { key ->
        val column = byKey[key]
        when {
            column == null -> problem("There is no column '$key' to sort on", names.sort, names.direction)
            !column.isSortable -> problem("The column ${column.header} cannot be sorted", names.sort, names.direction)
            else -> return@let column
        }
        null
    }
    val ascending = when (val direction = parameters.single(names.direction)) {
        null, "asc" -> true
        "desc" -> false
        else -> {
            problem("The sort direction must be 'asc' or 'desc', not '$direction'", names.direction)
            true
        }
    }
    val activeSort = if (sort != null) {
        ActiveSort(sort, ascending)
    } else if (parameters.single(names.sort) == null && defaultSort != null) {
        ActiveSort(byKey.getValue(defaultSort.key), defaultSort.ascending)
    } else {
        null
    }

    val cursor = parameters.single(names.cursor)?.let {
        try {
            QueryListCursor.parse(it)
        } catch (_: IllegalArgumentException) {
            problems.add(ParameterProblem("The page to show is not valid", setOf(names.cursor)))
            null
        }
    }

    return TableRequest(activeSort, filters, cursor, problems)
}

private fun <T : Any> parseFilter(
    parameters: Parameters,
    column: Column<T>,
    ownNames: List<String>,
    names: TableParameterNames,
    problem: (String, Array<out String>) -> Unit,
): ColumnFilter<T>? {
    val key = column.key!!
    val kind = column.kind!!
    var valid = true

    fun fail(message: String, name: String) {
        problem(message, arrayOf(name))
        valid = false
    }

    for (name in ownNames) {
        val allowed = when (name) {
            names.anyOf(key) -> true
            names.min(key), names.max(key) -> kind.hasRange
            names.contains(key) -> kind.hasContains
            else -> false
        }
        if (!allowed) {
            val operator = name.removePrefix(names.anyOf(key) + ".")
            fail("The column ${column.header} cannot be filtered with '$operator'", name)
        }
    }

    val anyOf = mutableSetOf<Any?>()
    val tokens = parameters.getAll(names.anyOf(key)).orEmpty()
        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    for (token in tokens) {
        when {
            token == NULL_TOKEN && column.nullable -> anyOf.add(null)
            !kind.hasAnyOf ->
                fail("${column.header} can only be filtered on a range or on being empty", names.anyOf(key))
            else -> kind.parse(token)?.let { anyOf.add(it) }
                ?: fail("'$token' is not a valid value for ${column.header}", names.anyOf(key))
        }
    }

    fun bound(name: String): Any? = parameters.single(name)?.let { text ->
        kind.parse(text)?.let { kind.sortKey(it) } ?: run {
            fail("'$text' is not a valid value for ${column.header}", name)
            null
        }
    }
    val min = if (kind.hasRange) bound(names.min(key)) else null
    val max = if (kind.hasRange) bound(names.max(key)) else null
    val contains = if (kind.hasContains) parameters.single(names.contains(key)) else null

    if (!valid || (anyOf.isEmpty() && min == null && max == null && contains == null)) {
        return null
    }
    return ColumnFilter(column, anyOf, min, max, contains)
}

/** The "any of" token that stands for an empty value. */
internal const val NULL_TOKEN = "null"

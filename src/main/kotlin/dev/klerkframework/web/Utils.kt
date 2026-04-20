package dev.klerkframework.web

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.QueryResponse
import dev.klerkframework.klerk.misc.ReflectedModel
import kotlinx.html.FormMethod
import kotlinx.html.HtmlBlockTag
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.details
import kotlinx.html.form
import kotlinx.html.onClick
import kotlinx.html.p
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlin.reflect.full.memberProperties

public data class TableConfig<M : Any>(
    val columns: List<Pair<String, (Model<M>) -> String>>,
    val pathProvider: (Model<M>) -> String,
    val caption: String? = null,
    val classProvider: CssClassProvider?,
    val ifEmptyText: String = "The list is empty"
)

public fun <M : Any> renderTable(queryResponse: QueryResponse<M>, config: TableConfig<M>): HtmlBlockTag.() -> Unit = {
    fun classesFor(element: String, model: Model<M>? = null) =
        config.classProvider?.tableOfModels(element, model)?.joinToString(" ")?.takeIf { it.isNotEmpty() }

    if (queryResponse.items.isEmpty()) {
        p(classesFor("p")) { +config.ifEmptyText }
    } else {
        table(classesFor("table")) {
            config.caption?.let { caption(classesFor("caption")) { +it } }
            thead(classesFor("thead")) {
                tr(classesFor("tr")) {
                    config.columns.forEach { column ->
                        th { +column.first }
                    }
                }
            }
            tbody(classesFor("tbody")) {
                queryResponse.items.forEach { model ->
                    val path = config.pathProvider(model)
                    tr(classesFor("tr", model)) {
                        onClick = """window.location = '$path';"""
                        config.columns.forEach { column ->
                            td(classesFor("td", model)) {
                                a(path) {
                                    +column.second(model)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

public fun <M : Any> renderModel(
    model: Model<M>,
    classProvider: CssClassProvider? = null,
    includeMetadata: Boolean = true,
): HtmlBlockTag.() -> Unit = {
    fun classesFor(element: String) =
        classProvider?.modelDetails(element)?.joinToString(" ")?.takeIf { it.isNotEmpty() }

    val reflected = ReflectedModel(model)
    table(classesFor("table")) {
        tbody(classesFor("tbody")) {
            (model.props::class).memberProperties.forEach { field ->
                tr(classesFor("tr")) {
                    td(classesFor("td")) { +field.name }
                    td(classesFor("td")) { +field.getter.call(model.props).toString() }
                }
            }
        }
    }
    if (includeMetadata) {
        details {
            summary { +"Metadata" }
            table {
                tbody {
                    reflected.getMeta().forEach { property ->
                        tr(classesFor("tr")) {
                            td(classesFor("td")) { apply(property.renderNameNonBreakingHtml()) }
                            td(classesFor("td")) {
                                property.description()?.let { title = it }
                                +property.toString()
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * Functions that return a set of CSS classes. Used to adapt the HTML output to your CSS.
 */
public interface CssClassProvider {
    public fun tableOfModels(element: String, model: Model<*>?): Set<String> {
        return setOf()
    }

    public fun modelDetails(element: String): Set<String> {
        return setOf()
    }
}

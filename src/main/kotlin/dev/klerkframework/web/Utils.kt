package dev.klerkframework.web

import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.misc.ReflectedModel
import kotlinx.html.*
import kotlin.reflect.full.memberProperties

/**
 * Renders the properties of one model as a table. Use [ModelDetailPage] instead when you also want event buttons and
 * links to related models.
 */
public fun <M : Any> FlowContent.modelProperties(
    model: Model<M>,
    classProvider: CssClassProvider? = null,
    includeMetadata: Boolean = true,
) {
    fun classesFor(element: String) = classProvider.attr(UiPart.ModelDetails, element, model = model)

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
 * Where in the generated HTML an element sits. Passed to [CssClassProvider] so that one provider can style
 * everything klerk-web renders.
 */
public enum class UiPart {
    /** A table listing models. */
    ModelTable,

    /** The properties of a single model. */
    ModelDetails,

    /** A generated form. */
    Form,
}

/**
 * Returns the CSS classes to put on an element that klerk-web renders. Return an empty set to leave it unstyled,
 * which is what a classless CSS expects.
 *
 * The same provider is used by every building block; [part] says which one is asking.
 *
 * @param part where the element sits.
 * @param element the HTML element name, e.g. "table", "td" or "input".
 * @param property the name of the model or parameter property the element belongs to, if any.
 * @param model the model being rendered, if any.
 */
public fun interface CssClassProvider {
    public fun classes(part: UiPart, element: String, property: String?, model: Model<*>?): Set<String>
}

/** The classes as an HTML class attribute value, or null when there are none. */
internal fun CssClassProvider?.attr(
    part: UiPart,
    element: String,
    property: String? = null,
    model: Model<*>? = null,
): String? = this?.classes(part, element, property, model)?.joinToString(" ")?.takeIf { it.isNotEmpty() }

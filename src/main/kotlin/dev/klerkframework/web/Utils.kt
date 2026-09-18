package dev.klerkframework.web

import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.misc.ObjectSchema
import kotlinx.html.*

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

    val reflected = ModelDisplay(model)
    table(classesFor("table")) {
        tbody(classesFor("tbody")) {
            for (field in ObjectSchema.of(model.props::class).fields) {
                tr(classesFor("tr")) {
                    td(classesFor("td")) { +field.name }
                    td(classesFor("td")) { +field.get(model.props).toString() }
                }
            }
        }
    }
    if (includeMetadata) {
        details {
            summary { +"Metadata" }
            table {
                tbody {
                    for (property in reflected.getMeta()) {
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
 * Provides the CSS classes to put on elements that klerk-web renders. The same provider is used by every
 * building block, so the [UiPart] argument says which one is asking.
 */
public fun interface CssClassProvider {
    /**
     * Returns the CSS classes to put on an [element] (e.g. "table", "td" or "input") that klerk-web renders in
     * [part]. [property] is the name of the model or parameter property the element belongs to, and [model] the model
     * being rendered, if any.
     *
     * Return an empty set to leave the element unstyled, which is what a classless CSS expects.
     */
    public fun classes(part: UiPart, element: String, property: String?, model: Model<*>?): Set<String>
}

/** The classes as an HTML class attribute value, or null when there are none. */
internal fun CssClassProvider?.attr(
    part: UiPart,
    element: String,
    property: String? = null,
    model: Model<*>? = null,
): String? = this?.classes(part, element, property, model)?.joinToString(" ")?.takeIf { it.isNotEmpty() }

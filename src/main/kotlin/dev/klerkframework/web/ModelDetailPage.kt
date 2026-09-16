package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.datatypes.AttachedDataContainer
import dev.klerkframework.klerk.datatypes.AttachedStringContainer
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.camelCaseToPretty
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlin.reflect.KClass

/**
 * A generated detail page for one model: its properties, its metadata, a button for every event that is possible in
 * the model's current state, and the models that refer to it.
 *
 * Register its route, or call [respond] from a route of your own.
 *
 * [eventLogPath] is where the history of a model can be seen, if anywhere. [extraContent] is rendered after the event
 * buttons. [useTable] renders the properties in a `<table>` instead of a `<dl>`.
 */
public class ModelDetailPage<T : Any, C : KlerkContext, V>(
    private val kClass: KClass<out Any>,
    private val support: WebSupport<C, V>,
    private val humanName: String,
    private val eventLogPath: String? = null,
    private val extraContent: ((KClass<out Any>, Model<Any>) -> DIV.() -> Unit)? = null,
    private val useTable: Boolean = false,
) {
    private val klerk: Klerk<C, V> = support.klerk
    private val pathProvider: PathProvider = support.pathProvider

    internal fun registerInto(route: Route) {
        with(route) {
            // A null path means the PathProvider says this model has no detail view, so no route is registered.
            pathProvider.pathForItem(kClass, "{id}")?.let { path ->
                get(path) {
                    respond(call)
                }
            }
        }
    }

    /**
     * Responds with the page, or a 404 if [id] no longer exists (e.g. the model was deleted), or a 403 if the actor
     * isn't authorized to read it.
     */
    public suspend fun respond(call: ApplicationCall) {
        val context = support.contextProvider(call, klerk)
        val id = ModelID<Any>(call.parameters["id"]!!.toInt())
        val (reflected, model, attachedHashes) = try {
            klerk.read(context) {
                val model = get(id)
                val reflectedModel = ModelDisplay(model)
                reflectedModel.populateRelations(this)
                Triple(reflectedModel, model, attachedDataHashes(reflectedModel))
            }
        } catch (e: NoSuchElementException) {
            support.respondPage(call, "Not found", HttpStatusCode.NotFound) { +"Not found" }
            return
        } catch (e: AuthorizationException) {
            support.respondPage(call, "Forbidden", HttpStatusCode.Forbidden) { +"Forbidden" }
            return
        }
        val events = klerk.read(context) { possibleEvents(id) }

        support.respondPage(call, "$humanName ${model.id}") {
            breadcrumbs(model.props::class, pathProvider, true)
            h1 { +camelCaseToPretty(requireNotNull(model.props::class.simpleName)) }

            apply(renderProperties(reflected, context, attachedHashes))
            apply(renderMeta(reflected))

            h3 { +"Commands" }
            for (event in events) {
                p {
                    eventButton(
                        event,
                        reflected.id,
                        context,
                        onCancelPath = pathProvider.base,
                        onSuccessAndModelExistPath = pathProvider.pathForItem(model.props::class, model.id),
                        onErrorPath = pathProvider.base,
                    )
                }
            }

            extraContent?.let { div { apply(it.invoke(kClass, reflected.original)) } }

            eventLogPath?.let { p { a(href = "$it?model=${reflected.id}") { button { +"History" } } } }

            apply(renderRelations(reflected))
        }
    }

    private fun classesFor(element: String, property: String? = null) =
        support.classProvider.attr(UiPart.ModelDetails, element, property)

    /** The name, with the description as a tooltip when there is one. */
    private fun propertyName(property: DisplayProperty, context: C): FlowOrPhrasingContent.() -> Unit = {
        val description = property.describe(context.translation.klerk)
        if (description != null) {
            span(classes = "tooltip") {
                attributes["data-description"] = description
                +property.name()
            }
        } else {
            +property.name()
        }
    }

    /**
     * The hash of every attached value on the model, keyed by its id, so that each can be linked to. A value the
     * actor may not read is absent, and is then rendered as plain text.
     *
     * Read inside the read block, so that the hashes come from the same snapshot as the model itself.
     */
    private fun Reader<C, V>.attachedDataHashes(reflected: ModelDisplay<Any>): Map<Any, String> {
        val result = mutableMapOf<Any, String>()
        for (property in reflected.getProperties()) {
            val value = property.value
            if (value !is AttachedDataContainer<*>) {
                continue
            }
            val entry: Pair<Any, String>? = when (value) {
                is AttachedBlobContainer ->
                    attachedData.getMetadataOrNull(value.id.untyped())?.let { value.id to it.hash }
                is AttachedStringContainer ->
                    attachedData.getMetadataOrNull(value.id.untyped())?.let { value.id to it.hash }
                else -> null
            }
            entry?.let { result[it.first] = it.second }
        }
        return result
    }

    /** The value, linked to its own detail page when it is a reference, or to its bytes when it is attached data. */
    private fun propertyValue(
        property: DisplayProperty,
        attachedHashes: Map<Any, String>,
    ): FlowOrPhrasingContent.() -> Unit = {
        val modelId = property.value

        @Suppress("UNCHECKED_CAST")
        val propsClass = property.getRelatedModelPropsClass() as? KClass<out Any>
        val href = when {
            modelId is ModelID<*> && propsClass != null ->
                pathProvider.pathForItem(propsClass, modelId.value.toString())
            modelId is AttachedBlobContainer -> attachedHashes[modelId.id]?.let {
                pathProvider.attachedDataPath(modelId.id, it)
            }
            modelId is AttachedStringContainer -> attachedHashes[modelId.id]?.let {
                pathProvider.attachedDataPath(modelId.id, it)
            }
            else -> null
        }
        val text = renderTemporalContainer(property.value) ?: property.toString()
        if (href != null) a(href = href) { +text } else +text
    }

    private fun renderProperties(
        reflected: ModelDisplay<Any>,
        context: C,
        attachedHashes: Map<Any, String>,
    ): FlowContent.() -> Unit = {
        if (useTable) {
            table(classesFor("table")) {
                tbody {
                    for (property in reflected.getProperties()) {
                        tr(classesFor("tr", property.name())) {
                            td(classesFor("td", property.name())) { apply(propertyName(property, context)) }
                            td(classesFor("td", property.name())) { apply(propertyValue(property, attachedHashes)) }
                        }
                    }
                }
            }
        } else {
            dl(classesFor("dl")) {
                for (property in reflected.getProperties()) {
                    dt(classesFor("dt", property.name())) { apply(propertyName(property, context)) }
                    dd(classesFor("dd", property.name())) { apply(propertyValue(property, attachedHashes)) }
                }
            }
        }
    }

    private fun renderMeta(reflected: ModelDisplay<Any>): FlowContent.() -> Unit = {
        details {
            summary { +"Meta" }
            dl {
                for (property in reflected.getMeta()) {
                    dt { apply(property.renderNameNonBreakingHtml()) }
                    dd {
                        property.description()?.let { title = it }
                        +property.toString()
                    }
                }
            }
        }
    }

    private fun renderRelations(reflected: ModelDisplay<Any>): FlowContent.() -> Unit = {
        for (relatedList in reflected.referencesPretty()) {
            details {
                summary { +relatedList.key }
                for (related in relatedList.value) {
                    +related.toString()
                }
            }
        }

        val referencesToThis = reflected.referencesToThis()
        if (referencesToThis?.isNotEmpty() == true) {
            details {
                summary { +"Related" }
                table {
                    thead {
                        tr {
                            th { +"Type" }
                            th { +"Name" }
                        }
                    }
                    tbody {
                        for (reference in referencesToThis) {
                            val target = reference.original.props::class
                            val href = pathProvider.pathForItem(target, reference.id)
                            tr {
                                td {
                                    val name = target.simpleName ?: "Unknown"
                                    if (href != null) a(href = href) { +name } else +name
                                }
                                td {
                                    if (href != null) a(href = href) { +"$reference" } else +"$reference"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun EventReference.urlEncode(): String = toString().encodeURLPathPart()

internal fun EventReference.Companion.urlDecode(encoded: String): EventReference = parse(encoded.decodeURLPart())

/** The detail route for one model. Registers nothing when [PathProvider.pathForItem] returns null for it. */
public fun <T : Any, C : KlerkContext, V> Route.modelDetailRoutes(page: ModelDetailPage<T, C, V>) {
    page.registerInto(this)
}

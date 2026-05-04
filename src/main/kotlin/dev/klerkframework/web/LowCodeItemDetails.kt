package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.ReflectedModel
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlin.reflect.KClass

internal class LowCodeItemDetails<T : Any, C : KlerkContext, V>(
    private val kClass: KClass<out Any>,
    private val config: AdminUI<C, V>,
    //private val modelPathPart: String,
    private val humanName: String,
    private val klerk: Klerk<C, V>,
    private val auditPath: String,
    private val pathProvider: PathProvider,
) {

    private lateinit var basePath: String

    fun initView(basePath: String) {
        this.basePath = basePath
    }

    fun registerRoutes(): Routing.() -> Unit = {
        get(pathProvider.pathForItem(kClass, "{id}")) {
            renderModel(call)
        }

    }

    private fun renderModelDetails(
        reflectedModel: ReflectedModel<Any>,
        eventReferences: Set<EventReference>,
        model: Model<Any>,
        context: C
    ): DIV.() -> Unit = {

        h3 { +"Details" }
        table {
            tbody {
                reflectedModel.getMeta().forEach { property ->
                    tr {
                        td { apply(property.renderNameNonBreakingHtml()) }
                        td {
                            property.description()?.let { title = it }
                            +property.toString()
                        }
                    }
                }
            }
        }

        apply(renderProperties(model.props))


        p { a(href = "$auditPath?model=${reflectedModel.id}") { button { +"History" } } }

        h3 { +"Commands" }

        eventReferences.forEach { event ->
            p {
                apply(
                    config.autoButtons.render(
                        event,
                        reflectedModel.id,
                        context,
                        onCancelPath = "/",
                        onSuccessAndModelExistPath = pathProvider.pathForItem(kClass, "{id}"),
                        onErrorPath = basePath
                    )
                )
            }
        }

        if (config.customAfterEventButtonsOnDetailView != null) {
            apply(config.customAfterEventButtonsOnDetailView.invoke(kClass, reflectedModel.original))
        }

        reflectedModel.referencesPretty().forEach { relatedList ->
            details {
                summary { +relatedList.key }
                relatedList.value.forEach {
                    +it.toString()
                }
            }
        }

        val referencesToThis = reflectedModel.referencesToThis()
        if (referencesToThis?.isNotEmpty() == true) {
            details {
                summary { +"Relations to this" }
                table {
                    tbody {
                        referencesToThis.forEach {
                            tr {
                                td { +"$it (id: ${it.id})" }
                            }
                        }
                    }
                }
            }
        }

    }

    private fun renderProperties(props: Any): DIV.() -> Unit = {
        // should change to YAML or HOCON ?
        val jsonPretty = klerk.config.toJson(props)
        textArea {
            disabled = true
            rows = "10" // jsonPretty.lines().size.toString()
            +jsonPretty
        }
    }


    internal suspend fun renderModel(call: ApplicationCall) {
        val context = config.contextProvider(call, klerk)
        val id = ModelID<Any>(call.parameters["id"]!!.toInt())
        val (reflectedModelPopulated, model) = klerk.read(context) {
            val model = get(id)
            val reflectedModel = ReflectedModel(model)
            apply(reflectedModel.populateRelations())
            Pair(reflectedModel, model)
        }

        val events = klerk.read(context) { getPossibleEvents(id) }

        call.respondHtml {
            apply(lowCodeHtmlHead(config))
            body {
                breadcumbs(basePath, model.props::class, pathProvider, true)
                div {
                    apply(renderModelDetails(reflectedModelPopulated, events, model, context))
                }
            }
        }
    }

}

internal fun EventReference.urlEncode(): String = id().encodeURLPathPart()

internal fun EventReference.Companion.urlDecode(encoded: String): EventReference = from(encoded.decodeURLPart())

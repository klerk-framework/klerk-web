package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.misc.ReflectedModel
import dev.klerkframework.klerk.misc.camelCaseToPretty
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import kotlinx.html.HtmlBlockTag
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.details
import kotlinx.html.h1
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.styleLink
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlin.collections.forEach
import kotlin.text.toInt

public class KlerkWeb<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C,
    private val cssPath: String,
    private val pathProvider: PathProvider = DefaultPathProvider(),
    private val classProvider: CssClassProvider? = null,
) {
    private val autoButtons = AutoButtons(klerk, "_autobuttons", contextProvider, cssPath)

    public fun generateNav(): HtmlBlockTag.() -> Unit = {
        nav {
            ul {
                klerk.config.managedModels.forEach { model ->
                    li {
                        a(href = pathProvider.pathForCollection(model.kClass)) { +"${model.kClass.simpleName}" }
                    }
                }
            }
        }
    }

    public fun generateRoutes(): Routing.() -> Unit = {
        klerk.config.managedModels.forEach { model ->
            get(pathProvider.pathForCollection(model.kClass)) {
                val lcl = LowCodeList<Any, C, V>(
                    model.kClass, adminUI, emptyList(),
                    pathProvider.pathForCollection(model.kClass),
                    humanName = camelCaseToPretty(model.kClass.simpleName ?: ""),
                    klerk,
                    pathProvider = pathProvider,
                )
                lcl.initView("/")
                lcl.renderModelList(call, adminUI)
            }

            get("${pathProvider.pathForCollection(model.kClass)}/{id}") {
                yetAnotherCopyOfRenderDetails(call)
            }
        }
    }

    private suspend fun yetAnotherCopyOfRenderDetails(call: RoutingCall) {
        val context = contextProvider(call, klerk)
        val id = ModelID<Any>(call.parameters["id"]!!.toInt())
        val (reflectedModelPopulated, model) = klerk.read(context) {
            val model = get(id)
            val reflectedModel = ReflectedModel(model)
            apply(reflectedModel.populateRelations())
            Pair(reflectedModel, model)
        }
        val events = klerk.read(context) { getPossibleEvents(id) }
        call.respondHtml {
            head {
                styleLink(cssPath)
            }
            body {
                breadcumbs("/", model.props::class, pathProvider, true)
                h1 { +requireNotNull(model.props::class.simpleName) }
                table {
                    tbody {
                        reflectedModelPopulated.getMeta().forEach { property ->
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

                val jsonPretty = klerk.config.toJson(model.props)
                textArea {
                    disabled = true
                    rows = "10" // jsonPretty.lines().size.toString()
                    +jsonPretty
                }

                h3 { +"Commands" }

                events.forEach { event ->
                    p {
                        apply(
                            autoButtons.render(
                                event,
                                reflectedModelPopulated.id,
                                context,
                                onCancelPath = "/",
                                onSuccessAndModelExistPath = pathProvider.pathForItem(model.props::class, model.id),
                                onErrorPath = "/"
                            )
                        )


                    }
                }

                reflectedModelPopulated.referencesPretty().forEach { relatedList ->
                    details {
                        summary { +relatedList.key }
                        relatedList.value.forEach {
                            +it.toString()
                        }
                    }
                }

                val referencesToThis = reflectedModelPopulated.referencesToThis()
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
                                referencesToThis.forEach {
                                    val target = it.original.props::class
                                    tr {
                                        td {
                                            a(href = pathProvider.pathForItem(target, it.id)) {
                                                +(target.simpleName ?: "Unknown")
                                            }
                                        }
                                        td {
                                            a(href = pathProvider.pathForItem(target, it.id)) {
                                                +"$it"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /*    private suspend fun yetAnotherRenderModelCollection(

            model: ManagedModel<*, *, C, V>,
            call: ApplicationCall
        ) {
            fun classesFor(element: String, model: Model<*>? = null) =
                classProvider?.tableOfModels(element, model)?.joinToString(" ")?.takeIf { it.isNotEmpty() }


            val ctx = contextProvider.invoke(call, klerk)

            val query = klerk.read(ctx) {
                query(model.collections.all)
            }

            call.respondHtml {
                body {
                    if (query.items.isEmpty()) {
                        p(classesFor("p")) { +"Empty" }
                    } else {
                        table(classesFor("table")) {
                            thead(classesFor("thead")) {
                                tr(classesFor("tr")) {
                                    config.columns.forEach { column ->
                                        th { +column.first }
                                    }
                                }
                            }
                            tbody(classesFor("tbody")) {
                                query.items.forEach { model ->
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
            }
        }


     */

    private val adminUI = AdminUI(
        klerk,
        basePath = "/admin",
        contextProvider = contextProvider,
        cssPath = cssPath,
        canSeeAdminUI = { true },
        autoButtons = autoButtons,
        pathProvider = DefaultPathProvider("/admin/"),
    )

}


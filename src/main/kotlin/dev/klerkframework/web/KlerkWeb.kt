package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.misc.ReflectedModel
import dev.klerkframework.klerk.misc.camelCaseToPretty
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*
import mu.KotlinLogging
import kotlin.reflect.KClass

private val log = KotlinLogging.logger {}

public class KlerkWeb<C : KlerkContext, V>(
    private val klerk: Klerk<C, V>,
    private val contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C,
    public val pathProvider: PathProvider = DefaultPathProvider(),
    public val adminPathProvider: PathProvider = DefaultPathProvider(
        pathProvider.base,
        "admin/",
        css = pathProvider.css,
        externalCssPath = pathProvider.externalCssPath
    ),
    private val classProvider: CssClassProvider? = null,
    public val autoButtons: AutoButtons<C, V> = AutoButtons(klerk, contextProvider, pathProvider),
    private val adminUI: AdminUI<C, V> = AdminUI(
        klerk,
        contextProvider,
        showOptionalParameters = { eventReference -> false },
        knownAlgorithms = setOf(),
        canSeeAdminUI = { true },   // TODO
        autoButtons = autoButtons,
        pathProvider = adminPathProvider
    ),
    private val useTableForDetails: Boolean = false,
) {

    public fun generateNav(
        translator: (ManagedModel<*, *, C, V>) -> String = { it.kClass.simpleName ?: "?" },
        filter: (ManagedModel<*, *, C, V>) -> Boolean = { true },
    ): HtmlBlockTag.() -> Unit = {
        nav {
            ul {
                klerk.config.managedModels.filter(filter).sortedBy { it.kClass.simpleName }.forEach { model ->
                    li {
                        a(href = pathProvider.pathForCollection(model.kClass)) { +translator(model) }
                    }
                }
            }
        }
    }

    public fun generateRoutes(): Routing.() -> Unit = {
        apply(autoButtons.registerRoutes())
        apply(adminUI.registerRoutes())
        klerk.config.managedModels.forEach { model ->
            log.info { "Registering route: ${pathProvider.pathForCollection(model.kClass)}" }
            get(pathProvider.pathForCollection(model.kClass)) {
                val lcl = LowCodeList<Any, C, V>(
                    model.kClass, adminUI, emptyList(),
                    pathProvider.pathForCollection(model.kClass),
                    humanName = camelCaseToPretty(model.kClass.simpleName ?: ""),
                    klerk,
                    pathProvider = pathProvider,
                )
                lcl.renderModelList(call, adminUI)
            }

            log.info { "Registering route: ${pathProvider.pathForCollection(model.kClass)}/{id}" }
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
                pathProvider.cssUrl()?.let { styleLink(it) }
            }
            body {
                breadcrumbs(model.props::class, pathProvider, true)
                h1 { +camelCaseToPretty(requireNotNull(model.props::class.simpleName)) }

                if (useTableForDetails) {
                    table {
                        tbody {
                            reflectedModelPopulated.getProperties().forEach {
                                val description = it.describe(context.translation.klerk)
                                tr {
                                    td {
                                        if (description != null) {
                                            span(classes = "tooltip") {
                                                attributes["data-description"] = description
                                                +it.name()
                                            }
                                        } else {
                                            +it.name()
                                        }
                                    }
                                    td {
                                        val modelId = it.value

                                        @Suppress("UNCHECKED_CAST")
                                        val propsClass = it.getRelatedModelPropsClass() as? KClass<out Any>
                                        if (modelId is ModelID<*> && propsClass != null) {
                                            a(
                                                href = pathProvider.pathForItem(
                                                    propsClass,
                                                    modelId.value.toString()
                                                )
                                            ) { +it.toString() }
                                        } else {
                                            +it.toString()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    dl {
                        reflectedModelPopulated.getProperties().forEach {
                            val description = it.describe(context.translation.klerk)
                            dt {
                                if (description != null) {
                                    span(classes = "tooltip") {
                                        attributes["data-description"] = description
                                        +it.name()
                                    }
                                } else {
                                    +it.name()
                                }
                            }
                            dd {
                                val modelId = it.value

                                @Suppress("UNCHECKED_CAST")
                                val propsClass = it.getRelatedModelPropsClass() as? KClass<out Any>
                                if (modelId is ModelID<*> && propsClass != null) {
                                    a(
                                        href = pathProvider.pathForItem(
                                            propsClass,
                                            modelId.value.toString()
                                        )
                                    ) { +it.toString() }
                                } else {
                                    +it.toString()
                                }
                            }
                        }
                    }
                }

                details {
                    summary { +"Meta" }

                    if (useTableForDetails) {

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
                    } else {
                        dl {
                            reflectedModelPopulated.getMeta().forEach { property ->
                                dt { apply(property.renderNameNonBreakingHtml()) }
                                dd {
                                    property.description()?.let { title = it }
                                    +property.toString()
                                }
                            }
                        }
                    }
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

    /*    private val adminUI = AdminUI(
            klerk,
            basePath = "/admin",
            contextProvider = contextProvider,
            cssPath = cssPath,
            canSeeAdminUI = { true },
            autoButtons = autoButtons,
            pathProvider = DefaultPathProvider("/admin/"),
        )

     */

}

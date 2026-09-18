package dev.klerkframework.web
import dev.klerkframework.klerk.AuthorizationConfig
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkPlugin
import dev.klerkframework.klerk.KlerkTranslation
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.generateFlowChart
import dev.klerkframework.klerk.misc.generateStateDiagram
import dev.klerkframework.klerk.statemachine.StateMachine
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import kotlinx.html.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.reflect.KClass

internal suspend fun <C : KlerkContext, V> renderDocumentation(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>,
    documentationPath: String,
    algorithms: Set<FlowChartAlgorithm<*, *>>,
) {
    val context = support.contextProvider(call, klerk)
    val showUpdateNotes = (call.request.queryParameters["showUpdateNotes"] ?: "false") == "true"
    val csrfToken = Csrf.issue(call)

    support.respondPage(call, "Documentation") {
        header {
            nav { div { a(href = support.pathProvider.base) { +"Home" } } }
        }
        val forModel = call.request.queryParameters["model"]
        if (forModel == null) {
            h1 { +"Documentation" }
            val translation = context.translation.klerk
            apply(renderModels(klerk.specification.managedModels, klerk, documentationPath, translation, csrfToken))
            apply(renderAuthorizationRules(klerk.specification.authorization, context.translation.klerk))
            apply(renderViews(klerk.specification.views))
            apply(renderPluginsDocumentation(klerk.specification.plugins))
            hr()
            ul {
                for (managedModel in klerk.specification.managedModels) {
                    li {
                        a(href = "$documentationPath?model=${managedModel.kClass.qualifiedName}") {
                            +managedModel.kClass.simpleName.toString()
                        }
                    }
                }
            }
        } else {
            val model = klerk.specification.managedModels.single { it.kClass.qualifiedName == forModel }
            h1 { +"Documentation for ${model.kClass.simpleName}" }
            pre(classes = "mermaid") {
                unsafe {
                    +generateStateDiagram(model.stateMachine, showUpdateNotes, context.translation.klerk)
                }
            }
            if (!showUpdateNotes) {
                a(href = call.request.uri.plus("&showUpdateNotes=true")) { +"Show updates" }
            } else {
                a(href = call.request.uri.replace("&showUpdateNotes=true", "")) { +"Hide updates" }
            }
            apply(addMermaidScript())
            apply(renderEvents(model.stateMachine, klerk, context.translation.klerk))
            // apply(renderStates(model.stateMachine, klerk, documentationPath))
        }
        apply(renderAlgorithms(documentationPath, algorithms))
    }
}

private fun <C : KlerkContext, V> renderPluginsDocumentation(
    plugins: List<KlerkPlugin<C, V>>,
): FlowContent.() -> Unit = {
    h2 { +"Plugins" }
    ul {
        for (plugin in plugins) {
            li { +"${plugin.name}: ${plugin.description}" }
        }
    }
}

private fun <C : KlerkContext, V> renderModels(
    models: Set<ManagedModel<*, *, C, V>>,
    klerk: Klerk<C, V>,
    documentationPath: String,
    translation: KlerkTranslation,
    csrfToken: String,
): FlowContent.() -> Unit = {
    apply(addMermaidScript())
    h2 { +"Models" }
    for (model in models) {
        h3 { +(model.kClass.simpleName ?: "") }
        apply(renderModelProperties(model.kClass, documentationPath, csrfToken))
        apply(renderStatemachine(model.stateMachine, klerk, translation))
    }
}

private fun <C : KlerkContext, V> renderStatemachine(
    stateMachine: StateMachine<out Any, out Enum<*>, C, V>,
    klerk: Klerk<C, V>,
    translation: KlerkTranslation,
): FlowContent.() -> Unit = {
    h4 { +"States, transitions and events" }
    pre(classes = "mermaid") {
        unsafe {
            +generateStateDiagram(stateMachine, false, translation)
        }
    }
    apply(renderEvents(stateMachine, klerk, translation))
}

private fun renderModelProperties(
    kClass: KClass<out Any>,
    documentationPath: String,
    csrfToken: String,
): FlowContent.() -> Unit = {
    h4 { +"Properties" }
    ul {
        for (prop in ObjectSchema.of(kClass).fields) {
            li { +prop.name }
            table {
                tr {
                    td { +"Nullable" }
                    td { +if (prop.isNullable) "yes" else "no" }
                }
                tr {
                    td { +"Base type" }
                    td { +prop.type.toString() }
                }
                tr {
                    td { +"Container" }
                    td {
                        span {
                            title = prop.valueClass.qualifiedName.orEmpty()
                            +prop.name
                        }
                    }
                }
                for (entry in prop.validationRulesDescriptions) {
                    tr {
                        td { +entry.key }
                        td { +entry.value }
                    }
                }
            }

            val propClass = prop.valueClass.qualifiedName.orEmpty()
            if (prop.type == PropertyType.String) {
                form("$documentationPath/functionInvocation", method = FormMethod.post) {
                    with(Csrf) { tokenInput(csrfToken) }
                    hiddenInput(name = FUNCTION_KIND) { value = DATA_CONTAINER_VALIDATION }
                    hiddenInput(name = DATA_CONTAINER_CLASS) { value = propClass }
                    hiddenInput(name = "name") { value = prop.name }
                    textInput(name = "value") { }
                    submitInput(classes = "button") { value = "Test validation" }
                }
            }
        }
    }
}

private fun <V> renderViews(views: V): FlowContent.() -> Unit = {
    h2 { +"Views" }
}

internal fun renderAlgorithms(
    documentationPath: String,
    algorithms: Set<FlowChartAlgorithm<*, *>>,
): FlowContent.() -> Unit = {
    h2 { +"Algorithms" }
    for (algorithm in algorithms) {
        val url = URLEncoder.encode(algorithm::class.qualifiedName, Charset.defaultCharset())
        a(href = "$documentationPath/algorithms/$url") { +algorithm::class.qualifiedName.toString() }
    }
}

private const val NO_BULLETS = "list-style-type: none;"

private fun <C : KlerkContext, V> renderEvents(
    stateMachine: StateMachine<out Any, out Enum<*>, C, V>,
    klerk: Klerk<C, V>,
    translation: KlerkTranslation,
): FlowContent.() -> Unit = {
    h5 { +"Events" }
    for (externalEvent in stateMachine.eventReferences) {
        val parameters = klerk.specification.parametersSchema(externalEvent)
        h6 { +externalEvent.toString() }
        ul {
            style = NO_BULLETS
            li {
                if (parameters == null) {
                    +"No parameters"
                } else {
                    details {
                        summary { +"Parameters" }
                        ul {
                            style = NO_BULLETS
                            for (parameter in parameters.fields) {
                                details {
                                    summary { +parameter.name }
                                    table {
                                        tr {
                                            td { +"Required" }
                                            td { +if (parameter.isRequired) "yes" else "no" }
                                        }
                                        tr {
                                            td { +"Nullable" }
                                            td { +if (parameter.isNullable) "yes" else "no" }
                                        }
                                        tr {
                                            td { +"Base type" }
                                            td { +parameter.type.toString() }
                                        }
                                        tr {
                                            td { +"Container" }
                                            td {
                                                span {
                                                    title = parameter.valueClass.qualifiedName.orEmpty()
                                                    +parameter.name
                                                }
                                            }
                                        }
                                        parameter.defaultContainer?.let {
                                            tr {
                                                td { +"Default value" }
                                                td { +it.toString() }
                                            }
                                        }

                                        for (entry in parameter.validationRulesDescriptions) {
                                            tr {
                                                td { +entry.key }
                                                td { +entry.value }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            li {
                details {
                    summary { +"Validation rules" }
                    ul {
                        for (rule in klerk.specification.contextRulesFor(externalEvent)) {
                            li {
                                +"Context: ${translation.function(rule)}"
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun <V> renderAuthorizationRules(
    specification: AuthorizationConfig<*, V>,
    translation: KlerkTranslation,
): FlowContent.() -> Unit = {
    h2 { +"Authorization rules" }
    h3 { +"Events" }
    h4 { +"Positive" }
    ul {
        for (rule in specification.eventPositiveRules) {
            li { +translation.function(rule) }
        }
    }
    h4 { +"Negative" }
    ul {
        for (rule in specification.eventNegativeRules) {
            li { +translation.function(rule) }
        }
    }

    h3 { +"Read models" }
    h4 { +"Positive" }
    ul {
        for (rule in specification.readModelPositiveRules) {
            li { +translation.function(rule) }
        }
    }
    h4 { +"Negative" }
    ul {
        for (rule in specification.readModelNegativeRules) {
            li { +translation.function(rule) }
        }
    }

    h3 { +"Read model properties" }
    h4 { +"Positive" }
    ul {
        for (rule in specification.readPropertyPositiveRules) {
            li { +translation.function(rule) }
        }
    }
    h4 { +"Negative" }
    ul {
        for (rule in specification.readPropertyNegativeRules) {
            li { +translation.function(rule) }
        }
    }

    h3 { +"Event log" }
    h4 { +"Positive" }
    ul {
        for (rule in specification.eventLogPositiveRules) {
            li { +translation.function(rule) }
        }
    }
    h4 { +"Negative" }
    ul {
        for (rule in specification.eventLogNegativeRules) {
            li { +translation.function(rule) }
        }
    }
}

internal suspend fun <C : KlerkContext, V> renderAlgorithm(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    algorithms: Set<FlowChartAlgorithm<*, *>>,
) {
    val algorithmName =
        URLDecoder.decode(call.parameters["name"], Charset.defaultCharset()) ?: throw IllegalArgumentException()
    val algorithm = algorithms.single { it::class.qualifiedName == algorithmName }

    support.respondPage(call, algorithm.name) {
        apply(addMermaidScript())
        h1 { +algorithm.name }
        pre(classes = "mermaid") {
            unsafe {
                +generateFlowChart(algorithm)
            }
        }
    }
}

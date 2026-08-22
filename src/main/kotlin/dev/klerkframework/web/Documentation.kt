package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.misc.AlgorithmDocumenter
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.extractNameFromFunction
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
    documentationPath: String
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
                apply(renderModels(klerk.specification.managedModels, klerk, documentationPath, context.translation.klerk, csrfToken))
                apply(renderAuthorizationRules(klerk.specification.authorization))
                apply(renderCollections(klerk.specification.views))
                apply(renderPluginsDocumentation(klerk.specification.plugins))
                hr()
                ul {
                    klerk.specification.managedModels.forEach { managedModel ->
                        li {
                            a(href = "$documentationPath?model=${managedModel.kClass.qualifiedName}") { +managedModel.kClass.simpleName.toString() }
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
                apply(renderEvents(model.stateMachine, klerk))
                // apply(renderStates(model.stateMachine, klerk, documentationPath))

            }
            apply(renderAlgorithms(documentationPath))
    }
}

private fun <C : KlerkContext, V> renderPluginsDocumentation(plugins: List<KlerkPlugin<C, V>>): FlowContent.() -> Unit = {
    h2 { +"Plugins" }
    ul {
        plugins.forEach { plugin ->
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
    models.forEach { model ->
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
    apply(renderEvents(stateMachine, klerk))

}

private fun renderModelProperties(kClass: KClass<out Any>, documentationPath: String, csrfToken: String): FlowContent.() -> Unit = {
    h4 { +"Properties" }
    ul {
        EventParameters(kClass).all.forEach { prop ->
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
                            title = prop.qualifiedName
                            +prop.name
                        }
                    }
                }
                prop.validationRulesDescriptions.forEach { entry ->
                    tr {
                        td { +entry.key }
                        td { +entry.value }
                    }
                }
            }

            val propClass = prop.qualifiedName
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

private fun <V> renderCollections(collections: V): FlowContent.() -> Unit = {
    h2 { +"Collections" }

}

internal fun renderAlgorithms(documentationPath: String): FlowContent.() -> Unit = {
    h2 { +"Algorithms" }
    AlgorithmDocumenter.algorithms.forEach {
        val url = URLEncoder.encode(it::class.qualifiedName, Charset.defaultCharset())
        a(href = "$documentationPath/algorithms/${url}") { +it::class.qualifiedName.toString() }
    }
}

private const val noBullets = "list-style-type: none;"

private fun <C : KlerkContext, V> renderEvents(
    stateMachine: StateMachine<out Any, out Enum<*>, C, V>,
    klerk: Klerk<C, V>
): FlowContent.() -> Unit =
    {
        h5 { +"Events" }
        stateMachine.getAllEvents().forEach { externalEvent ->
            val parameters = klerk.specification.getParameters(externalEvent)
            h6 { +externalEvent.id() }
            ul {
                style = noBullets
                li {
                    if (parameters == null) {
                        +"No parameters"
                    } else {
                        details {
                            summary { +"Parameters" }
                            ul {
                                style = noBullets
                                parameters.all.forEach { parameter ->
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
                                                        title = parameter.qualifiedName
                                                        +parameter.name
                                                    }
                                                }
                                            }
                                            parameter.recommendedDefaultValue?.let {
                                                tr {
                                                    td { +"Recommended default value" }
                                                    td { +it.toString() }
                                                }
                                            }

                                            parameter.validationRulesDescriptions.forEach { entry ->
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
                            klerk.specification.getEvent(externalEvent).getContextRules<C>().forEach {
                                li {
                                    +"Context: ${extractNameFromFunction(it)}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }


internal fun <V> renderAuthorizationRules(specification: AuthorizationConfig<*, V>): FlowContent.() -> Unit = {
    h2 { +"Authorization rules" }
    h3 { +"Events" }
    h4 { +"Positive" }
    ul {
        specification.eventPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        specification.eventNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }

    h3 { +"Read models" }
    h4 { +"Positive" }
    ul {
        specification.readModelPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        specification.readModelNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }

    h3 { +"Read model properties" }
    h4 { +"Positive" }
    ul {
        specification.readPropertyPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        specification.readPropertyNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }

    h3 { +"Event log" }
    h4 { +"Positive" }
    ul {
        specification.eventLogPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        specification.eventLogNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
}


internal suspend fun <C : KlerkContext, V> renderAlgorithm(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<*, V>
) {
    val algorithmName =
        URLDecoder.decode(call.parameters["name"], Charset.defaultCharset()) ?: throw IllegalArgumentException()
    val algorithm = AlgorithmDocumenter.getAlgorithm(algorithmName)

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

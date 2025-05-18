package dev.klerkframework.web

import dev.klerkframework.klerk.AuthorizationConfig
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkPlugin
import dev.klerkframework.klerk.ManagedModel

import dev.klerkframework.klerk.misc.AlgorithmDocumenter
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.statemachine.*
import generateFlowChart
import generateStateDiagram
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
    config: LowCodeConfig<C>,
    klerk: Klerk<C, V>,
    documentationPath: String
) {
    val showUpdateNotes = (call.request.queryParameters["showUpdateNotes"] ?: "false") == "true"

    call.respondHtml {
        apply(lowCodeHtmlHead(config))
        body {
            header {
                nav { div { a(href = config.basePath) { +"Home" } } }
            }
            val forModel = call.request.queryParameters["model"]
            if (forModel == null) {
                h1 { +"Documentation" }
                apply(renderModels(klerk.config.managedModels, klerk, documentationPath))
                apply(renderAuthorizationRules(klerk.config.authorization))
                apply(renderCollections(klerk.config.collections))
                apply(renderPluginsDocumentation(klerk.config.plugins))
                hr()
                ul {
                    klerk.config.managedModels.forEach { managedModel ->
                        li {
                            a(href = "$documentationPath?model=${managedModel.kClass.qualifiedName}") { +managedModel.kClass.simpleName.toString() }
                        }
                    }
                }

            } else {
                val model = klerk.config.managedModels.single { it.kClass.qualifiedName == forModel }
                h1 { +"Documentation for ${model.kClass.simpleName}" }
                pre(classes = "mermaid") {
                    unsafe {
                        +generateStateDiagram(model.stateMachine, showUpdateNotes)
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
}

private fun <C : KlerkContext, V> renderPluginsDocumentation(plugins: List<KlerkPlugin<C, V>>): BODY.() -> Unit = {
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
): BODY.() -> Unit = {
    apply(addMermaidScript())
    h2 { +"Models" }
    models.forEach { model ->
        h3 { +(model.kClass.simpleName ?: "") }
        apply(renderModelProperties(model.kClass, documentationPath))
        apply(renderStatemachine(model.stateMachine, klerk))
    }
}

private fun <C : KlerkContext, V> renderStatemachine(
    stateMachine: StateMachine<out Any, out Enum<*>, C, V>,
    klerk: Klerk<C, V>
): BODY.() -> Unit = {
    h4 { +"States, transitions and events" }
    pre(classes = "mermaid") {
        unsafe {
            +generateStateDiagram(stateMachine, false)
        }
    }
    apply(renderEvents(stateMachine, klerk))

}

private fun renderModelProperties(kClass: KClass<out Any>, documentationPath: String): BODY.() -> Unit = {
    h4 { +"Properties" }
    ul {
        EventParameters(kClass).all.forEach { prop ->
            li { +prop.name }
            table {
                tr {
                    td { +"Required" }
                    td { +if (prop.isRequired) "yes" else "no" }
                }
                tr {
                    td { +"Nullable" }
                    td { +if (prop.isNullable) "yes" else "no" }
                }
                tr {
                    td { +"Primitive type" }
                    td { +prop.type.toString() }
                }
                tr {
                    td { +"Class" }
                    td { +prop.valueClass.toString() }
                }
                prop.validationRulesDescription().forEach { entry ->
                    tr {
                        td { +entry.key }
                        td { +entry.value }
                    }
                }
            }

            val propClass = prop.valueClass.qualifiedName
            if (prop.type == PropertyType.String && propClass != null) {
                form("$documentationPath/functionInvocation", method = FormMethod.post) {
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

private fun <V> renderCollections(collections: V): BODY.() -> Unit = {
    h2 { +"Collections" }
    print(collections)

}

internal fun renderAlgorithms(documentationPath: String): BODY.() -> Unit = {
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
): BODY.() -> Unit =
    {
        h5 { +"Events" }
        stateMachine.getExternalEvents().forEach { externalEvent ->
            val parameters = klerk.config.getParameters(externalEvent)
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
                                                td { +"Primitive type" }
                                                td { +parameter.type.toString() }
                                            }
                                            tr {
                                                td { +"Class" }
                                                td { +parameter.valueClass.toString() }
                                            }
                                            parameter.validationRulesDescription().forEach { entry ->
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
                            klerk.config.getEvent(externalEvent).getContextRules<C>().forEach {
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


internal fun <V> renderAuthorizationRules(config: AuthorizationConfig<*, V>): BODY.() -> Unit = {
    h2 { +"Authorization rules" }
    h3 { +"Events" }
    h4 { +"Positive" }
    ul {
        config.eventPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        config.eventNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }

    h3 { +"Read models" }
    h4 { +"Positive" }
    ul {
        config.readModelPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        config.readModelNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }

    h3 { +"Read model properties" }
    h4 { +"Positive" }
    ul {
        config.readPropertyPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        config.readPropertyNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }

    h3 { +"Event log" }
    h4 { +"Positive" }
    ul {
        config.eventLogPositiveRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
    h4 { +"Negative" }
    ul {
        config.eventLogNegativeRules.forEach {
            li { +extractNameFromFunction(it) }
        }
    }
}


internal suspend fun <C : KlerkContext, V> renderAlgorithm(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    klerk: Klerk<*, V>
) {
    val algorithmName =
        URLDecoder.decode(call.parameters["name"], Charset.defaultCharset()) ?: throw IllegalArgumentException()
    val algorithm = AlgorithmDocumenter.getAlgorithm(algorithmName)

    call.respondHtml {
        head {}
        body {
            apply(addMermaidScript())
            h1 { +algorithm.name }
            pre(classes = "mermaid") {
                unsafe {
                    +generateFlowChart(algorithm)
                }
            }
        }
    }
}

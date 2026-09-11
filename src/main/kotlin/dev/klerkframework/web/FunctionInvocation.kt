package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import kotlinx.html.body

internal const val FUNCTION_KIND = "function-kind"
internal const val DATA_CONTAINER_VALIDATION = "data-container-validation"
internal const val DATA_CONTAINER_CLASS = "data-container-class"

/**
 * Renders a form so that the admin can test the various functions in the configuration.
 */
internal suspend fun <C : KlerkContext, V> renderFunctionInvocation(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)
    val requestParams = Csrf.receiveVerifiedParameters(call) ?: return
    val type = requestParams[FUNCTION_KIND] ?: throw IllegalArgumentException("No function-kind provided")
    if (type == DATA_CONTAINER_VALIDATION) {
        val property = requestParams[DATA_CONTAINER_CLASS] ?: throw IllegalArgumentException("No class provided")
        val name = requestParams["name"] ?: throw IllegalArgumentException("No name provided")
        val value = requestParams["value"] ?: throw IllegalArgumentException("No value provided")
        val field = klerk.spec.managedModels.flatMap { ObjectSchema.of(it.kClass).fields }
            .firstOrNull { it.type == PropertyType.String && it.valueClass.qualifiedName == property }
        if (field == null) {
            support.respondPage(call, "Function") { +"No property found with name $property" }
            return
        }

        val problem = field.createContainer(value).validate(name, context.translation)
        if (problem != null) {
            support.respondPage(call, "Function") { +"Validation problem: $problem" }
            return
        }
        support.respondPage(call, "Function") { +"Validation successful" }
        return
    }
    support.respondPage(call, "Function") { +"Not implemented" }

}

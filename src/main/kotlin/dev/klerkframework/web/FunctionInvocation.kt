package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType

internal const val FUNCTION_KIND = "function-kind"
internal const val DATA_CONTAINER_VALIDATION = "data-container-validation"
internal const val DATA_CONTAINER_CLASS = "data-container-class"

/**
 * Renders a form so that the admin can test the various functions in the configuration.
 */
internal suspend fun <C : KlerkContext, V> renderFunctionInvocation(
    call: ApplicationCall,
    config: LowCodeConfig<C>,
    klerk: Klerk<C, V>
) {
    val requestParams = call.receiveParameters()
    val type = requestParams[FUNCTION_KIND] ?: throw IllegalArgumentException("No function-kind provided")
    if (type == DATA_CONTAINER_VALIDATION) {
        val property = requestParams[DATA_CONTAINER_CLASS] ?: throw IllegalArgumentException("No class provided")
        val name = requestParams["name"] ?: throw IllegalArgumentException("No name provided")
        val value = requestParams["value"] ?: throw IllegalArgumentException("No value provided")
        val prop = klerk.config.managedModels.flatMap { it.kClass.memberProperties }
            .firstOrNull { it.returnType.toString() == property }
        if (prop == null) {
            call.respondHtml {
                +"No property found with name $property"
            }
            return
        }

        if (prop.returnType.isSubtypeOf(StringContainer::class.starProjectedType)) {
            val container = (prop.returnType.classifier as KClass<*>).constructors.first().call(value) as DataContainer<*>
            val problem = container.validate(name)
            if (problem != null) {
                call.respondHtml {
                    +"Validation problem: $problem"
                }
                return
            }
            call.respondHtml { +"Validation successful" }
            return
        }

    }
    call.respondHtml { +"Not implemented" }

}

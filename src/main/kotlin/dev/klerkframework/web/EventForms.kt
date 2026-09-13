package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.SchemaField
import dev.klerkframework.klerk.misc.camelCaseToPretty
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import kotlinx.html.*
import java.time.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import mu.KotlinLogging
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

internal class LowCodeCreateEvent<C : KlerkContext, V>(
    private val support: WebSupport<C, V>,
    private val path: String,
    internal val eventReference: EventReference,
    internal val modelClass: KClass<out Any>,
    autoButtons: AutoButtons<C, V>,
) {
    private val logger = KotlinLogging.logger {}
    private val klerk: Klerk<C, V> = support.klerk

    /**
     * Built eagerly so that an event klerk-web cannot render fails when the application starts, rather than when a
     * user clicks the button.
     */
    private val template: FormTemplate<out Any, C, V>

    init {
        val parameters = requireNotNull(klerk.specification.getParameters(eventReference))
        try {
            template = FormTemplate(
                EventWithParameters(eventReference, parameters),
                //eventWithParameters.parameters.raw,
                klerk,
                getUrl(),
                classProvider = support.classProvider,
                autoButtons = autoButtons,
                pathProvider = support.pathProvider,
                layout = support.layout,
            ) {
                remaining()
            }
        } catch (e: NotImplementedError) {
            throw IllegalStateException(
                "klerk-web cannot build a form for the event '$eventReference': ${e.message}. " +
                        "Either add support for the data type, or exclude the event from the generated UI.", e
            )
        }
    }

    override fun toString(): String = eventReference.toString()

    internal fun getUrl(): String {
        return "$path?eventId=${eventReference.id().encodeURLPathPart()}"
    }

    internal companion object {
        internal suspend fun <C : KlerkContext, V> renderCreateEventPage(
            call: ApplicationCall,
            createCommandsWithParams: List<LowCodeCreateEvent<C, V>>,
            support: WebSupport<C, V>,
        ) {
            val klerk = support.klerk
            val pathProvider = support.pathProvider
            val context = support.contextProvider(call, klerk)
            val queryParameters = call.request.queryParameters
            val completionPaths = CompletionPaths.parse(call, null)
            val id = queryParameters["modelId"]?.let { ModelID<Any>(it.toInt()) }
            val eventReference = EventReference.urlDecode(requireNotNull(queryParameters["eventId"]))
            val parameters = klerk.specification.getParameters(eventReference)

            val eventHeading = headingFor(eventReference)
            if (parameters == null) {
                // The event takes no parameters, so there is nothing to fill in. A form is still rendered, because
                // that is what carries the CSRF token - an event is never triggered by a link.
                renderParameterlessEventForm(call, eventReference, id, completionPaths, support, eventHeading)
                return
            }
            val eventWithParameters = EventWithParameters(eventReference, parameters)

            val lowCodeCreateEvent =
                createCommandsWithParams.singleOrNull { it.eventReference == eventWithParameters.eventReference }
            if (lowCodeCreateEvent == null) {
                // Either excluded by the application, or klerk-web cannot render a form for it (reported at startup).
                support.respondPage(call, "Not found", HttpStatusCode.NotFound) { +"Not found" }
                return
            }

            @Suppress("UNCHECKED_CAST")
            val template = lowCodeCreateEvent.template as FormTemplate<Any, C, V>

            val modelIdQueryParams = if (id != null) mapOf("modelId" to id.toString()) else emptyMap
            val form = klerk.read(context) {
                // TODO: this if statement should not check for "Update", that specification should be injected into the template somehow. A function?
                val params = if (eventReference.eventName.contains("Update") && eventReference.modelName == eventWithParameters.parameters.kClass.simpleName && id != null) get(id).props else null
                template.build(
                    call,
                    params = params,
                    reader = this,
                    queryParams = completionPaths.toQueryParams().plus(modelIdQueryParams),
                    translator = context.translation,
                    context = context,
                )
            }

            support.respondPage(call, eventHeading) {
                main {
                    h1 { +eventHeading }
                    eventForm(form)
                    a(completionPaths.cancel) {
                        button {
                            +"Cancel"
                        }
                    }
                }
            }
        }

        private fun headingFor(eventReference: EventReference): String {
            val eventName = camelCaseToPretty(eventReference.eventName)
            val modelName = camelCaseToPretty(eventReference.modelName)
            return if (eventName.lowercase().contains(modelName.lowercase())) eventName else "$eventName ($modelName)"
        }

        /**
         * A confirmation form for an event without parameters. Its only purpose is to carry the CSRF token and the
         * idempotence key.
         */
        private suspend fun <C : KlerkContext, V> renderParameterlessEventForm(
            call: ApplicationCall,
            eventReference: EventReference,
            id: ModelID<Any>?,
            completionPaths: CompletionPaths,
            support: WebSupport<C, V>,
            heading: String,
        ) {
            val csrfToken = Csrf.issue(call)
            val queryParams = completionPaths.toQueryParams()
                .plus("eventId" to eventReference.urlEncode())
                .plus(if (id != null) mapOf("modelId" to id.toString()) else emptyMap())
            val action =
                "${support.pathProvider.autoButtons}?${queryParams.map { "${it.key}=${it.value}" }.joinToString("&")}"
            support.respondPage(call, heading) {
                main {
                    h1 { +heading }
                    form(action = action, method = FormMethod.post) {
                        with(Csrf) { tokenInput(csrfToken) }
                        hiddenInput(name = IDEMPOTENCE_KEY) { value = CommandToken.simple().toString() }
                        submitInput { value = "Ok" }
                    }
                    a(completionPaths.cancel) {
                        button { +"Cancel" }
                    }
                }
            }
        }

        internal suspend fun <C : KlerkContext, V> renderExecuteEvent(
            call: ApplicationCall,
            createCommandsWithParams: List<LowCodeCreateEvent<C, V>>,
            support: WebSupport<C, V>,
        ) {
            val klerk = support.klerk
            val pathProvider = support.pathProvider
            val context = support.contextProvider(call, klerk)
            val queryParameters = call.request.queryParameters
            //  val buttonTargets = parseButtonTargets(call, null)
            val eventReference = EventReference.from(requireNotNull(queryParameters["eventId"]))
            val id = queryParameters["modelId"]?.let { ModelID<Any>(it.toInt()) }
            //val eventWithParameters = EventWithParameters(eventReference, requireNotNull(klerk.specification.getParameters(eventReference)))
            val parameters = klerk.specification.getParameters(eventReference)
            val event = klerk.specification.getEvent(eventReference)

            val template = createCommandsWithParams.singleOrNull { it.eventReference == eventReference }?.template

            if (parameters != null && template == null) {
                // Either excluded by the application, or klerk-web cannot render a form for it (reported at startup).
                support.respondPage(call, "Not found", HttpStatusCode.NotFound) { +"Not found" }
                return
            }

            if (template == null) {
                // An event without parameters: no form was parsed, so the CSRF token is verified here.
                val params = Csrf.receiveVerifiedParameters(call) ?: return
                val key = params[IDEMPOTENCE_KEY]?.let { CommandToken.from(it) } ?: CommandToken.simple()
                executeEvent(
                    event,
                    id,
                    klerk,
                    context,
                    call,
                    support,
                    key,
                    null
                )
            } else {
                when (val parseResult = template.parse(call, context)) {
                    is ParseResult.Forbidden -> FormTemplate.respondForbidden(call)
                    is ParseResult.Invalid -> FormTemplate.respondInvalid(parseResult, call)
                    is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
                    is ParseResult.Parsed -> {
                        executeEvent(
                            event,
                            id,
                            klerk,
                            context,
                            call,
                            support,
                            parseResult.key,
                            parseResult.params
                        )
                    }
                }
            }
        }

        private suspend fun <C : KlerkContext, V> executeEvent(
            event: Event<Any, Any?>,
            id: ModelID<Any>?,
            klerk: Klerk<C, V>,
            context: C,
            call: ApplicationCall,
            support: WebSupport<C, V>,
            key: CommandToken,
            params: Any?
        ) {
            val options = ProcessingOptions(token = key)
            val command = when (event) {
                is InstanceEventNoParameters -> Command(event, id, null)
                is InstanceEventWithParameters -> Command(event, id, params!!)
                is VoidEventNoParameters -> Command(event, null, null)
                is VoidEventWithParameters -> Command(event, null, params!!)
            }

            when (val eventResult = klerk.handle(command, context, options)) {
                is CommandResult.Failure -> {
                    support.respondPage(call, "Bad request") {
                        h1 { +"Bad request" }
                        val violatedRule = eventResult.problems.firstNotNullOfOrNull { it.violatedRule }
                        if (violatedRule != null) {
                            div {
                                +"$violatedRule (${violatedRule.type})"
                            }
                        } else {
                            div { +(eventResult.problems.firstOrNull()?.endUserTranslatedMessage ?: "Unknown error") }
                        }
                    }
                }

                is CommandResult.Success -> {
                    val modelId = id ?: eventResult.createdModels.firstOrNull()
                    val modelWasDeleted = eventResult.deletedModels.contains(modelId)
                    val completionPaths = CompletionPaths.parse(call, modelId)
                    support.respondPage(call, "Done") {
                        h3 { +"Event was executed" }
                        apply(renderSuccess(eventResult))
                        br
                        a(href = if (modelId == null || modelWasDeleted) completionPaths.cancel else completionPaths.model) {
                            button { +"Ok" }
                        }
                    }
                }
            }
        }
    }

}

internal class AuthenticationException(message: String? = null) : RuntimeException(message)

/**
 * The value submitted for [field], as what the parameters class takes: a DataContainer, a ModelID or an
 * AttachedBlobID. Null if nothing, or an empty selection, was submitted.
 */
internal fun valueWithCorrectType(value: String?, field: SchemaField): Any? {
    if (field.type == PropertyType.Boolean) {
        // If multiple values are sent (checkbox + hidden), Ktor's callParams[name] returns the first one.
        // If checkbox is checked, it's "true". If unchecked, only "false" (from hidden) is present.
        if (value == null) {
            return if (field.isNullable) null else field.createContainer(false)
        }
        return field.createContainer(value.toBoolean())
    }
    if (value == null) {
        return null
    }
    try {
        return when (field.type) {
            PropertyType.Ref -> if (value.isEmpty()) null else ModelID<Any>(value.toInt())
            // The application's own container class, or a bare id, around the blob the form resolved.
            PropertyType.AttachedDataRef -> when {
                value.isEmpty() -> null
                field.valueClass == AttachedBlobID::class -> AttachedBlobID(value.toInt())
                else -> field.createContainer(AttachedBlobID(value.toInt()))
            }

            PropertyType.Enum ->
                if (value.isEmpty()) null else field.createContainer(field.enumConstants.first { it.name == value })

            PropertyType.Int -> field.createContainer(value.toInt())
            PropertyType.Long -> field.createContainer(value.toLong())
            PropertyType.Float -> field.createContainer(value.toFloat())
            PropertyType.Instant -> {
                // A datetime-local input sends local time without a zone, so it is interpreted in the server's time zone.
                val local = LocalDateTime.parse(if (value.count { it == ':' } == 1) "$value:00" else value)
                field.createContainer(local.toInstant(TimeZone.currentSystemDefault()))
            }

            PropertyType.Date -> field.createContainer(LocalDate.parse(value))
            PropertyType.Duration -> field.createContainer(value.toLong().seconds)
            else -> field.createContainer(value)
        }
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Could not parse '${field.name}'", e)
    }
}


/**
 * Check if the parameter class is the same as the model class. However, we don't really know for sure that it is
 * intended to be used as an update...
 */
private suspend fun <C : KlerkContext, V> isCrudUpdate(
    id: ModelID<Any>?,
    e: EventWithParameters<*>,
    context: C,
    klerk: Klerk<C, V>
): Boolean {
    if (id == null) return false
    val model = klerk.read(context) { get(id) }
    return model.props::class == e.parameters.kClass
}

private fun renderSuccess(result: CommandResult.Success<out Any>): FlowContent.() -> Unit = {
    result.createdModels.forEach { p { +"Model (id: $it) was created" } }
    result.updatedModels.forEach { p { +"Model (id: $it) was updated" } }
    result.deletedModels.forEach { p { +"Model (id: $it) was deleted" } }
    result.jobs.forEach { p { +"Job (id: $it) was created" } }
}

/**
 * Utility to encode and decode the button targets from query parameters.
 *
 * @param cancel The path to redirect to when the user clicks the cancel button. Also used when the command was
 * executed successfully but there is no relevant model (e.g. it was deleted).
 * @param model The path to redirect to when the command was executed successfully and the model exists.
 * @param error The path to redirect to when an error occurs.
 */
public data class CompletionPaths(internal val cancel: String, internal val model: String?, internal val error: String) {

    internal fun toQueryParams(): Map<String, String> {
        if (model == null) {
            return mapOf(
                "bt_cancel" to Base64.UrlSafe.encode(cancel.encodeToByteArray()),
                "bt_error" to Base64.UrlSafe.encode(error.encodeToByteArray())
            )
        }
        return mapOf(
            "bt_cancel" to Base64.UrlSafe.encode(cancel.encodeToByteArray()),
            "bt_model" to Base64.UrlSafe.encode(model.encodeToByteArray()),
            "bt_error" to Base64.UrlSafe.encode(error.encodeToByteArray())
        )
    }

    internal fun toQueryParamsString(): String = toQueryParams().map { "${it.key}=${it.value}" }.joinToString("&")

    internal companion object {
        fun parse(call: ApplicationCall, id: ModelID<out Any>?): CompletionPaths {
            var parsedModelPath = call.request.queryParameters["bt_model"]?.let { Base64.UrlSafe.decode(it).decodeToString() } ?: "/"
            if (id != null) {
                parsedModelPath = parsedModelPath.replace("{id}", id.toString())
            }
            return CompletionPaths(
                cancel = call.request.queryParameters["bt_cancel"]?.let { Base64.UrlSafe.decode(it).decodeToString() } ?: "/",
                model = parsedModelPath,
                error = call.request.queryParameters["bt_error"]?.let { Base64.UrlSafe.decode(it).decodeToString() } ?: "/",
            )
        }
    }
}

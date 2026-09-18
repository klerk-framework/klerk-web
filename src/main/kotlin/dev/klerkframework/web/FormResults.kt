package dev.klerkframework.web
import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.InvalidPropertyCollectionProblem
import dev.klerkframework.klerk.InvalidPropertyProblem
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.VoidEvent
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ValResponse(
    val propertyProblems: List<ValidationProblemResponse>,
    val propertyCollectionProblems: List<String>,
    val fieldsMustBeNull: Set<String>,
    val fieldsMustNotBeNull: Set<String>,
)

/** One problem in a validation response. [field] is null when the problem is not tied to a single input. */
@Serializable
public data class ValidationProblemResponse(public val field: String?, public val humanReadable: String)

/** The outcome of [FormTemplate.parse]. */
public sealed class ParseResult<T> {
    /** The request failed the CSRF check and must not be acted on. Respond with [FormTemplate.respondForbidden]. */
    public class Forbidden<T> : ParseResult<T>()

    /** The submitted data is not valid. Respond with [FormTemplate.respondInvalid]. */
    public data class Invalid<T>(val problems: Set<Problem>) : ParseResult<T>()

    /** The request was a dry run, so respond without issuing the command. */
    public data class DryRun<T>(val params: T, val key: CommandToken) : ParseResult<T>()

    /** Valid parameters. Pass [key] as the [dev.klerkframework.klerk.command.ProcessingOptions] token. */
    public data class Parsed<T>(val params: T, val key: CommandToken) : ParseResult<T>()
}

/** A reference parameter and the models that may be selected for it. */
public data class ReferencePropertyWithOptions(
    val propertyName: String,
    val propertyNullable: Boolean,
    val options: List<Model<out Any>>,
    val suggestedEvents: Collection<VoidEvent<*, *>>,
)

/** An enum parameter and the values that may be selected for it. */
public data class EnumPropertyWithOptions(
    val propertyName: String,
    val propertyNullable: Boolean,
    val options: Set<Enum<*>>,
)

/**
 * Runs the command as a dry run and responds with the problems, in the shape the bundled form validation script
 * expects. Use it when [FormTemplate.parse] returns [ParseResult.DryRun].
 */
public suspend fun <M : Any, P : Any, C : KlerkContext, D> respondDryRun(
    params: P,
    key: CommandToken,
    event: VoidEventWithParameters<M, P>,
    call: ApplicationCall,
    klerk: Klerk<C, D>,
    context: C,
) {
    val command = Command(event, params)
    when (val result = klerk.handle(command, context, ProcessingOptions(key, dryRun = true))) {
        is CommandResult.Failure -> {
            val fieldProblems = result.problems
                .filterIsInstance<InvalidPropertyProblem>()
                .map { ValidationProblemResponse(it.propertyName, it.endUserTranslatedMessage ?: "?") }

            val propertyCollectionProblems = result.problems
                .filterIsInstance<InvalidPropertyCollectionProblem>()
                .map { it.endUserTranslatedMessage ?: "Unknown problem" }

            // The details of a failure (violated rule names, model contents) stay on the server. The user is only
            // told that the event is not possible.
            fileLog.info { "Dry run failed: $result" }
            val remainingProblems = result.problems
                .filterNot { it is InvalidPropertyProblem || it is InvalidPropertyCollectionProblem }
            val dryRunProblems = if (remainingProblems.isEmpty()) emptyList() else listOf("Not allowed")

            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.UnprocessableEntity,
                text = Json.encodeToString(
                    ValidationResponse.serializer(),
                    ValidationResponse(
                        propertyProblems = fieldProblems,
                        propertyCollectionProblems = propertyCollectionProblems,
                        formProblems = emptyList(),
                        dryRunProblems = dryRunProblems,
                    ),
                ),
            )
        }

        is CommandResult.Success -> call.respond(HttpStatusCode.OK)
    }
}

/**
 * The JSON that the bundled form validation script expects. Note that [ValResponse] must have the same shape.
 */
@Serializable
public data class ValidationResponse(
    val propertyProblems: List<ValidationProblemResponse>,
    val propertyCollectionProblems: List<String>,
    val formProblems: List<String>,
    val dryRunProblems: List<String>,
)

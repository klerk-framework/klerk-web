package dev.klerkframework.web
import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.BadRequestProblem
import dev.klerkframework.klerk.BlobRejectedException
import dev.klerkframework.klerk.InvalidPropertyCollectionProblem
import dev.klerkframework.klerk.InvalidPropertyProblem
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.datatypes.AttachedBlobContainer
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.SchemaField
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.asSequence
import dev.klerkframework.web.upload.Upload
import dev.klerkframework.web.upload.UploadPlugin
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.html.*
import kotlinx.html.InputType.checkBox
import kotlinx.html.InputType.dateTimeLocal
import kotlinx.html.InputType.email
import kotlinx.html.InputType.hidden
import kotlinx.html.InputType.number
import kotlinx.html.InputType.password
import kotlinx.html.InputType.text
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.InputStream
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.time.Duration.Companion.minutes

internal val fileLog = KotlinLogging.logger {}

internal val CSRF_TOKEN = Csrf.TOKEN_NAME

/** How long a blob prepared from an upload waits for its command. Long enough to fix a form and submit again. */
private val UPLOAD_BLOB_LEASE = 15.minutes

/** Above this, a reference select would be unusable anyway; see the TODO where it is enforced. */
private const val MAX_REFERENCE_OPTIONS = 300

internal val IDEMPOTENCE_KEY: String = if (isDevelopmentMode()) "idempotence-key" else "__Host-idempotence-key"

/** Passed to a form's label provider. */
public data class UIElementData(val propertyName: String, val dataContainer: DataContainer<*>, val enabled: Boolean)

/**
 * Regarding CSRF protection: the 'Double Submit Pattern' with '__Host-' cookie-prefix is used.
 */
public class FormTemplate<T : Any, C : KlerkContext, V>(
    internal val defaultValues: EventWithParameters<T>,
    internal val klerk: Klerk<C, V>,
    private val postPath: String? = null,
    internal val classProvider: CssClassProvider? = null,
    internal val autoButtons: AutoButtons<C, V>? = null,
    internal val pathProvider: PathProvider,
    internal val layout: Layout = Layout(assetsBase = pathProvider.assetsBase),
    /** Required for [file]: the plugin that received the bytes, so that [parse] can turn an upload into a blob. */
    internal val uploads: UploadPlugin<C, V>? = null,
    init: FormTemplate<T, C, V>.() -> Unit,
) {
    private val log = KotlinLogging.logger {}

    internal val parameters: ObjectSchema<T> = defaultValues.parameters

    /*private val items = mutableListOf<Item<T>>()
    private val hidden = mutableMapOf<String, Any>()
     */
    private val inputs = mutableListOf<Pair<String, InputType>>()
    private val selectReferences = mutableListOf<String>()
    private val selectEnums = mutableListOf<String>()

    // private val emailInputs = mutableListOf<String>()
    private val propsPopulatedAfterSubmit = mutableListOf<String>()
    private val fileInputs = mutableListOf<String>()
    private var htmlDetailsSummary: String? = null
    private val htmlDetailsContents = mutableSetOf<String>()
    internal var labelProvider: ((UIElementData) -> String?)? = null

    init {
        this.init()
        validate()
    }

    /** A text input for [property]. */
    public fun text(property: KProperty1<*, StringContainer?>) {
        inputs.add(Pair(property.name, text))
    }

    private fun text(parameter: SchemaField) = inputs.add(Pair(parameter.name, text))

    /** An email input for [property]. */
    public fun email(property: KProperty1<*, StringContainer?>) {
        inputs.add(Pair(property.name, email))
    }

    private fun email(parameter: SchemaField) =
        inputs.add(Pair(parameter.name, email)) // use <input type="text" inputmode="email"> instead?

    /** A password input for [property]. */
    public fun password(property: KProperty1<*, StringContainer?>) {
        inputs.add(Pair(property.name, password))
    }

    private fun password(parameter: SchemaField) = inputs.add(Pair(parameter.name, password))

    /** A number input for [property]. */
    public fun number(property: KProperty1<*, DataContainer<*>?>) {
        inputs.add(Pair(property.name, number))
    }

    private fun number(parameter: SchemaField) = inputs.add(Pair(parameter.name, number))

    /** A checkbox for [property]. */
    public fun checkbox(property: KProperty1<*, BooleanContainer?>) {
        inputs.add(Pair(property.name, checkBox))
    }

    private fun checkbox(parameter: SchemaField) = inputs.add(Pair(parameter.name, checkBox))

    /** A `datetime-local` input. The value is interpreted in the browser's time zone. */
    public fun dateTime(property: KProperty1<*, InstantContainer?>) {
        inputs.add(Pair(property.name, dateTimeLocal))
    }

    private fun dateTime(parameter: SchemaField) = inputs.add(Pair(parameter.name, dateTimeLocal))

    /** A `date` input — a calendar date with no time of day or time zone. */
    public fun date(property: KProperty1<*, DateContainer?>) {
        inputs.add(Pair(property.name, InputType.date))
    }

    private fun date(parameter: SchemaField) = inputs.add(Pair(parameter.name, InputType.date))

    /** A number of seconds. */
    public fun duration(property: KProperty1<*, DurationContainer?>) {
        inputs.add(Pair(property.name, number))
    }

    private fun duration(parameter: SchemaField) = inputs.add(Pair(parameter.name, number))

    /** A hidden input for [property], whose value comes from the params the form is built with. */
    public fun hidden(property: KProperty1<*, Any?>) {
        inputs.add(Pair(property.name, hidden))
    }

    /**
     * A file input for an [AttachedBlobID] parameter.
     *
     * The bytes do not travel with the form. The browser uploads them to [UploadPlugin]'s routes first — resumably,
     * in chunks — and the form carries only the id of the resulting [dev.klerkframework.web.upload.Upload] in a
     * hidden field. [parse] turns that into attached data and puts the [AttachedBlobID] in the parameters, so the
     * command that stores it is as quick as any other.
     *
     * Requires an `uploads` plugin on the template. Without JavaScript the field still works: the file is posted with
     * the form and uploaded in one request before the parameters are parsed.
     */
    public fun file(property: KProperty1<*, AttachedBlobContainer?>) {
        fileInputs.add(property.name)
    }

    /**
     * The [AttachedBlobContainer] class the property holds, which is what `prepare` needs in order to know what the
     * file must be and what has to happen to it first.
     *
     * [blobDeclarationFor] answers "what does this property declare"; this answers "which declaration is it".
     */
    internal fun blobClassFor(propertyName: String): KClass<out AttachedBlobContainer> {
        val field = parameters.field(propertyName)?.takeIf { !it.isCollection }
        return field?.blobDeclaration ?: throw IllegalArgumentException(
            "file('$propertyName') needs a parameter of an AttachedBlobContainer type, but " +
                "${parameters.kClass.simpleName}.$propertyName is " +
                "${field?.valueClass?.simpleName ?: "not a parameter at all"}",
        )
    }

    /**
     * The [AttachedBlobContainer] declared for [propertyName], so that the form can render what it says.
     *
     * The blob id it is given is a placeholder: what is wanted here is the declaration, not a particular value.
     */
    internal fun blobDeclarationFor(propertyName: String): AttachedBlobContainer? {
        val field = parameters.field(propertyName)?.takeIf { !it.isCollection } ?: return null
        if (field.blobDeclaration == null) {
            return null
        }
        return runCatching { field.dummyContainer() as AttachedBlobContainer }
            .onFailure { e -> log.warn(e) { "Could not read the declaration of $propertyName" } }.getOrNull()
    }

    /** A select of the models [property] may refer to. */
    public fun selectReference(property: KProperty1<*, ModelID<out Any>?>) {
        selectReferences.add(property.name)
    }

    private fun selectReference(parameter: SchemaField) = selectReferences.add(parameter.name)

    private fun selectEnum(parameter: SchemaField) = selectEnums.add(parameter.name)

    /*    fun selectEnum(property: KProperty1<*, EnumContainer<*>>) = selectEnums.add(property.name)
        private fun selectEnum(parameter: SchemaField) = selectEnums.add(parameter.name)
     */

    /** Leaves [property] out of the form. Your code supplies its value when the submitted form is parsed. */
    public fun populatedAfterSubmit(property: KProperty1<*, Any?>) {
        propsPopulatedAfterSubmit.add(property.name)
    }

    /**
     * Adds an input of the default kind for every property not declared yet. If [inHtmlDetails] is given, they are
     * rendered in a collapsed `<details>` with that summary.
     */
    public fun remaining(inHtmlDetails: String? = null) {
        htmlDetailsSummary = inHtmlDetails
        val remaining = parameters.fields
            .filter { p -> inputs.none { input -> input.first == p.name } }
            .filter { p -> selectReferences.none { select -> select == p.name } }
            .filter { p -> selectEnums.none { select -> select == p.name } }
            .filter { p -> propsPopulatedAfterSubmit.none { it == p.name } }
            .filter { p -> fileInputs.none { it == p.name } }

        if (inHtmlDetails != null) {
            htmlDetailsContents.addAll(remaining.map { it.name })
        }

        remaining
            .forEach { p ->
                when (p.type) {
                    PropertyType.String -> text(p)
                    PropertyType.Boolean -> checkbox(p)
                    PropertyType.Int, PropertyType.Long, PropertyType.Short, PropertyType.Byte,
                    PropertyType.UInt, PropertyType.ULong, PropertyType.UShort, PropertyType.UByte,
                    PropertyType.Float, PropertyType.Double, PropertyType.Decimal,
                    -> number(p)

                    PropertyType.Ref -> selectReference(p)
                    PropertyType.Enum -> selectEnum(p)
                    PropertyType.Instant -> dateTime(p)
                    PropertyType.Date -> date(p)
                    PropertyType.Duration -> duration(p)
                    PropertyType.AttachedDataRef -> throw IllegalStateException(
                        "The property '${p.name}' refers to attached data. Declare it with file() if it is an " +
                            "AttachedBlobID the user should upload, or with hidden()/populatedAfterSubmit() if " +
                            "your own code supplies it. remaining() never renders one by itself, since uploading " +
                            "requires the Uploads plugin.",
                    )

                    else -> throw IllegalStateException(
                        "klerk-web cannot render the property '${p.name}' of type '${p.type?.name ?: "unknown"}'." +
                            " If you are writing the form yourself, declare that property with hidden() or" +
                            " populatedAfterSubmit() instead of leaving it to remaining().",
                    )
                }
            }
    }

    /** Builds a form for one request, pre-filled with [params] if given. Render it with [eventForm]. */
    public fun build(
        call: ApplicationCall,
        params: T?,
        reader: Reader<C, *>,
        modelIDSelects: Map<KProperty1<*, ModelID<out Any>?>, ModelView<out Any, C>> = emptyMap(),
        // enumSelects: Map<KProperty1<*, EnumContainer<*>>, Array<out Enum<*>>>? = null,
        path: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        translator: Translation,
        context: C,
    ): EventForm<T, C, V> {
        val csrfToken = Csrf.issue(call)

        return EventForm(
            csrfToken,
            inputs,
            populateMissingReferenceSelects(modelIDSelects, reader, inputs, context),
            populateEnumSelects(),
            propsPopulatedAfterSubmit,
            fileInputs,
            params,
            path ?: postPath,
            queryParams,
            htmlDetailsSummary = htmlDetailsSummary,
            htmlDetailsContents = htmlDetailsContents,
            this,
            translator,
            context,
            call.request.uri,
        )
    }

    private fun populateEnumSelects(): Set<EnumPropertyWithOptions> {
        val result = mutableSetOf<EnumPropertyWithOptions>()
        parameters.fields
            .filter { selectEnums.contains(it.name) }
            .forEach { eventParameter ->
                val validEnums = klerk.specification.validEnumsFor(defaultValues.eventReference, eventParameter)
                    ?: eventParameter.enumConstants.toSet()
                result.add(EnumPropertyWithOptions(eventParameter.name, eventParameter.isNullable, validEnums))
            }
        return result
    }

    private fun populateMissingReferenceSelects(
        developerProvidedModelIDSelects: Map<KProperty1<*, ModelID<out Any>?>, ModelView<out Any, C>>,
        reader: Reader<C, *>,
        inputs: List<Pair<String, InputType>>,
        context: C,
    ): Set<ReferencePropertyWithOptions> {
        val result = mutableSetOf<ReferencePropertyWithOptions>()
        parameters.fields
            .filter { it.type == PropertyType.Ref }
            // A Ref already rendered as hidden() or left to populatedAfterSubmit() must not also get a select - the
            // caller already decided how its value is supplied.
            .filterNot { p -> inputs.any { input -> input.first == p.name } }
            .filterNot { p -> propsPopulatedAfterSubmit.contains(p.name) }
            .map { eventParameter ->
                val ls = klerk.specification.validReferencesFor(defaultValues.eventReference, eventParameter)
                    ?: return@map
                // Only offer references the actor may read; an unreadable row must not turn the form into a 500.
                // One past the limit, so "too many" can be told from "exactly the limit" -- and lazily, so a large
                // collection is not read in full just to be cut down here.
                val options = with(reader) { ls.asSequence().take(MAX_REFERENCE_OPTIONS + 1).toList() }
                if (options.size > MAX_REFERENCE_OPTIONS) {
                    TODO("Too many options")
                } else {
                    // suggestedEvents can be used if there is a need to first create a model that is then used in this
                    // event.
                    val referencedModel = eventParameter.referencedModel
                    val suggestedEvents = if (options.isNotEmpty() || referencedModel == null) {
                        emptyList()
                    } else {
                        with(reader) { possibleVoidEvents(referencedModel) }
                    }
                    result.add(
                        ReferencePropertyWithOptions(
                            eventParameter.name,
                            eventParameter.isNullable,
                            options,
                            suggestedEvents,
                        ),
                    )
                }
            }

        result.addAll(
            developerProvidedModelIDSelects.map { entry ->
                ReferencePropertyWithOptions(
                    entry.key.name,
                    entry.key.returnType.isMarkedNullable,
                    with(reader) { entry.value.asSequence().toList() },
                    emptyList(),
                )
            },
        )
        return result
    }

    internal fun validate() {
        if (klerk.specification.parametersSchema(defaultValues.eventReference) != defaultValues.parameters) {
            log.warn { "Trying to make a form for an event that doesn't match the parameters" }
        }

        if (fileInputs.isNotEmpty() && uploads == null) {
            throw IllegalStateException(
                "The form declares file(${fileInputs.joinToString(", ")}) but has no 'uploads' plugin. Pass the " +
                    "UploadPlugin to the FormTemplate, and register its routes.",
            )
        }

        val missing = parameters.fields
            .filterNot { prop -> inputs.map { i -> i.first }.contains(prop.name) }
            .filterNot { prop -> selectReferences.contains(prop.name) }
            .filterNot { prop -> selectEnums.contains(prop.name) }
            .filterNot { prop -> propsPopulatedAfterSubmit.contains(prop.name) }
            .filterNot { prop -> fileInputs.contains(prop.name) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Form for class ${parameters.kClass} is missing declaration for ${
                    missing.map { it.name }.joinToString(", ")
                }",
            )
        }
        val tooMany = inputs // also selects and other stuff...
            .map { i -> i.first }
            .filterNot { i -> parameters.fields.map { it.name }.contains(i) }
        if (tooMany.isNotEmpty()) {
            throw IllegalStateException(
                "Form for class ${parameters.kClass} contains too many declarations: ${
                    tooMany.joinToString(
                        ", ",
                    )
                }",
            )
        }
    }

    /**
     * Parses a submitted form.
     *
     * Validation happens in levels and stops at the first level that produces problems, since validating the
     * parameters together is meaningless until each parameter is valid on its own. All problems within a level are
     * reported, so the user gets every offending field marked at once. The remaining level - the authorization and
     * business rules - is evaluated by Klerk when the command is issued.
     *
     * The messages the user will read are translated according to [context].
     */
    public suspend fun parse(
        call: ApplicationCall,
        context: C,
        // not only DataContainer, also references. Collections?
        populatedAfterSubmit: Map<KProperty1<*, Any?>, DataContainer<*>> = emptyMap(),
    ): ParseResult<T> {
        // The form validation script submits the form on every change, so a dry run happens while the user is still
        // filling the form in - and must leave the file alone. See [DRY_RUN_BLOB].
        val isDryRun = call.request.queryParameters["dryRun"]?.equals("true") == true

        // A form with a file field posts as multipart when there is no JavaScript, and as an ordinary form when the
        // browser has already uploaded the bytes. Either way, what reaches the parameters below is a blob id.
        //
        // Nothing is resolved before the CSRF check: turning an upload into attached data is real work on behalf of a
        // real actor, and a request that fails the check must not cause any of it.
        val callParams = if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
            try {
                receiveMultipartAsParameters(call, context, isDryRun)
            } catch (e: UploadRefused) {
                // A file the property or the application's rules will not have. The user gets the reason, not a 500.
                val problem = BadRequestProblem(e.message ?: "The file was refused", KlerkErrorCode.NotFound)
                return ParseResult.Invalid(setOf(problem))
            }
        } else {
            val submitted = call.receiveParameters()
            if (!Csrf.isValid(call, submitted[CSRF_TOKEN])) {
                return ParseResult.Forbidden()
            }
            resolveUploads(submitted, context, isDryRun)
        }
        if (!Csrf.isValid(call, callParams[CSRF_TOKEN])) {
            return ParseResult.Forbidden()
        }
        val key = callParams[IDEMPOTENCE_KEY]?.let { CommandToken.parse(it) }
            ?: throw IllegalArgumentException("Missing input: $IDEMPOTENCE_KEY")

        for (name in callParams.names()) {
            val expected = name == CSRF_TOKEN ||
                name == IDEMPOTENCE_KEY ||
                name.startsWith("null-toggle-") ||
                inputs.any { it.first == name } ||
                name in selectReferences ||
                name in selectEnums ||
                name in fileInputs
            if (!expected) {
                throw IllegalArgumentException("Parameter $name is not expected to be present in request")
            }
        }

        val allParams = callParams.plus(
            ParametersBuilder().apply {
                for (p in populatedAfterSubmit) {
                    append(p.key.name, p.value.valueWithoutAuthorization.toString())
                }
            }.build(),
        )

        // someday maybe:
        // https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
        // #verifying-origin-with-standard-headers
        try {
            @Suppress("UNCHECKED_CAST")
            val paramsClass = createParamClassFromCallParameters(parameters, allParams) as T

            // Level 1: each property on its own.
            val propertyValidationProblems = mutableSetOf<InvalidPropertyProblem>()
            for (field in parameters.fields) {
                val container = field.get(paramsClass) as? DataContainer<*> ?: continue
                container.validate(field.name, context.translation)?.let { propertyValidationProblems.add(it) }
            }

            if (propertyValidationProblems.isNotEmpty()) {
                return ParseResult.Invalid(propertyValidationProblems.toSet())
            }

            // Level 2: the properties together. Klerk builds the message, so the form shows what the command would.
            val validationProblems = parameters.validatables(paramsClass)
                .flatMap { it.validators() }
                .mapNotNull { rule ->
                    (rule.invoke() as? PropertyCollectionValidity.Invalid)?.toProblem(rule, context.translation)
                }
                .toList()

            if (validationProblems.isNotEmpty()) {
                return ParseResult.Invalid(validationProblems.toSet())
            }

            if (isDryRun) {
                return ParseResult.DryRun(paramsClass, key)
            }
            return ParseResult.Parsed(paramsClass, key)
        } catch (e: Exception) {
            return ParseResult.Invalid(
                setOf(
                    BadRequestProblem(
                        e.message ?: "Could not parse",
                        KlerkErrorCode.Internal,
                    ),
                ),
            )
        }
    }

    /**
     * Replaces each file field's upload id with the id of the attached data it became.
     *
     * This happens when JavaScript is used on the client.
     *
     * The browser uploaded the bytes before submitting, so all that happens here is a lookup, a hand-over to attached
     * data and a substitution. With a file blob store on the staging filesystem the hand-over is a rename, so the
     * size of the file stops mattering here.
     *
     * The blob is leased for a few minutes rather than the usual one: a user may sit on a form with problems in it
     * for a while, and every attempt should not have to upload the file again.
     *
     * A dry run ([isDryRun]) stands the file in with [DRY_RUN_BLOB] instead, leaving the upload untouched.
     */
    private suspend fun resolveUploads(submitted: Parameters, context: C, isDryRun: Boolean): Parameters {
        if (fileInputs.isEmpty()) {
            return submitted
        }
        val plugin = requireNotNull(uploads) { "The form has a file field but no uploads plugin" }
        val builder = ParametersBuilder()
        // A plain loop rather than forEach: resolving an upload suspends, and StringValues.forEach is not inline.
        for (name in submitted.names()) {
            val values = submitted.getAll(name) ?: continue
            if (!fileInputs.contains(name)) {
                for (value in values) {
                    builder.append(name, value)
                }
                continue
            }
            val uploadId = values.firstOrNull()?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: continue
            if (isDryRun) {
                builder.appendBlob(name, DRY_RUN_BLOB)
                continue
            }
            val blob = withContext(Dispatchers.IO) {
                plugin.toAttachedData(
                    context,
                    ModelID(uploadId),
                    declaration = blobClassFor(name),
                    lease = UPLOAD_BLOB_LEASE,
                )
            }
            builder.appendBlob(name, blob)
        }
        return builder.build()
    }

    /**
     * A form carrying a file field, which is how a browser submits one whether or not the script ran.
     *
     * A file field arrives in one of two shapes, and both end as a blob id in the parameters:
     *
     * * as a **file part**, when the script never ran. There is nothing to resume in a single request, so the part is
     *   streamed straight into attached data rather than through the staging area — an upload that cannot be
     *   interrupted needs no [dev.klerkframework.web.upload.Upload] to track it.
     * * as a **form field holding an upload id**, when the script uploaded the bytes beforehand. The enctype is on the
     *   form either way, so this is the ordinary case, not the exception.
     *
     * A dry run ([isDryRun]) stands the file in with [DRY_RUN_BLOB] instead, so nothing is stored. The application's
     * own upload rule is still consulted, since that is what the user is asking about.
     */
    private suspend fun receiveMultipartAsParameters(call: ApplicationCall, context: C, isDryRun: Boolean): Parameters {
        val builder = ParametersBuilder()
        // File fields submitted as an id rather than as bytes. Resolved after the parts, so that the CSRF token is
        // known to be valid first — it may be the very last field for all this code knows.
        val submittedUploads = mutableMapOf<String, String>()
        val fromFileParts = mutableSetOf<String>()

        // Why the refusal is remembered rather than thrown: the rest of the request has to be read whatever we decide
        // about the file. Abandoning the body mid-stream leaves the client writing to a socket nobody is draining,
        // which for a large file is a hang rather than an error.
        var refused: String? = null

        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    val name = part.name
                    if (name != null && fileInputs.contains(name)) {
                        if (part.value.isNotBlank()) {
                            submittedUploads[name] = part.value
                        }
                    } else {
                        builder.append(name ?: "", part.value)
                    }
                }

                is PartData.FileItem -> {
                    val name = part.name
                    // The token is rendered before the file input, so by the time a file part arrives it has been
                    // seen. A request that puts the file first is refused rather than uploaded and then rejected.
                    val authorized = Csrf.isValid(call, builder[CSRF_TOKEN])
                    if (authorized &&
                        name != null &&
                        fileInputs.contains(name) &&
                        part.originalFileName?.isNotBlank() == true
                    ) {
                        val declaration = blobDeclarationFor(name)

                        // Ask the application's own rules before a byte is stored. This path creates no Upload — a
                        // single request has nothing to resume — so without this the rule on CreateUpload would apply
                        // to one path and not the other. Content-Length covers the whole request, so it is an
                        // over-estimate of the file, which is the safe direction for a limit.
                        val refusal = uploads?.reasonUploadWouldBeRefused(
                            context = context,
                            filename = part.originalFileName ?: "upload",
                            declaredContentType = part.contentType?.toString() ?: "application/octet-stream",
                            declaredSize = call.request.contentLength() ?: 0,
                        )
                        if (refusal != null) {
                            refused = refusal.endUserTranslatedMessage
                        } else if (isDryRun) {
                            // The bytes are read and dropped: what the user is being told is whether the rest of the
                            // form is in order, and storing the file to answer that would store it twice.
                            builder.appendBlob(name, DRY_RUN_BLOB)
                            fromFileParts.add(name)
                        } else {
                            try {
                                val blob = withContext(Dispatchers.IO) {
                                    // Cut off at what the property allows rather than storing gigabytes and refusing
                                    // them at claim time. A partially written value is discarded by prepare itself.
                                    val bytes = part.provider().toInputStream()
                                    val limit = declaration?.maxSize ?: Long.MAX_VALUE
                                    klerk.attachedData.prepare(
                                        LimitedInputStream(bytes, limit),
                                        blobClassFor(name),
                                        context,
                                    )
                                }
                                // The property's steps run in a job, but this request waits for them: without
                                // JavaScript there is nothing to poll for the result with.
                                klerk.attachedData.awaitProcessing(blob)
                                builder.appendBlob(name, blob)
                                fromFileParts.add(name)
                            } catch (e: UploadRefused) {
                                refused = e.message
                            } catch (e: BlobRejectedException) {
                                // A step said no. Same treatment as a size refusal: the body is still drained, and
                                // the user is told why.
                                refused = e.reason
                            }
                        }
                    }
                }

                else -> Unit
            }
            part.dispose()
        }

        refused?.let { throw UploadRefused(it) }

        if (submittedUploads.isNotEmpty() && Csrf.isValid(call, builder[CSRF_TOKEN])) {
            val plugin = requireNotNull(uploads) { "The form has a file field but no uploads plugin" }
            for ((name, uploadId) in submittedUploads) {
                // A field that also arrived as bytes has already been dealt with; the bytes win.
                val id = uploadId.toIntOrNull()?.takeIf { !fromFileParts.contains(name) } ?: continue
                if (isDryRun) {
                    builder.appendBlob(name, DRY_RUN_BLOB)
                    continue
                }
                val blob = withContext(Dispatchers.IO) {
                    plugin.toAttachedData(
                        context,
                        ModelID<Upload>(id),
                        declaration = blobClassFor(name),
                        lease = UPLOAD_BLOB_LEASE,
                    )
                }
                builder.appendBlob(name, blob)
            }
        }
        return builder.build()
    }

    /** Decides the label of each input. Returning null keeps the default label. */
    public fun labelProvider(labelProvider: (UIElementData) -> String?) {
        this.labelProvider = labelProvider
    }

    private class TextInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, StringContainer?>,
        val labelText: String,
        val datatypeValue: StringContainer,
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.value
                required = !property.returnType.isMarkedNullable
                datatypeValue.minLength?.apply { minLength = this.toString() }
                datatypeValue.maxLength?.apply { maxLength = this.toString() }
                if (datatypeValue.regexPattern != null) {
                    pattern = datatypeValue.regexPattern!!.toString()
                } // for some reason, the apply didn't work
            }
        }

        override fun getName() = property.name
    }

    private class CheckboxInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: BooleanContainer,
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = "true"
                checked = datatypeValue.value
            }
            tag.input(InputType.hidden) {
                name = property.name
                value = "false"
            }
        }

        override fun getName() = property.name
    }

    private class DateItem<T>(
        val property: KProperty1<*, String?>,
        val labelText: String,
        val minFunction: (() -> LocalDate)?,
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.dateInput {
                id = property.name
                name = property.name
                required = !property.returnType.isMarkedNullable
                if (minFunction != null) {
                    min = minFunction.invoke().toString()
                }
                // maxFunction
            }
        }

        override fun getName() = property.name
    }

    private class TextAreaItem<T>(
        val property: KProperty1<*, String?>,
        val labelText: String,
        val rowsSettings: Int = 10,
        val columns: Int = 30,
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.textArea {
                id = property.name
                name = property.name
                required = !property.returnType.isMarkedNullable
                rows = rowsSettings.toString()
                cols = columns.toString()
            }
        }

        override fun getName() = property.name
    }

    private class HiddenItem<T>(val property: KProperty1<*, Any?>, val valueString: String) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            tag.hiddenInput {
                id = property.name
                name = property.name
                value = valueString
            }
        }

        override fun getName() = property.name
    }

    internal interface Item<T> {
        fun render(tag: HtmlBlockTag, params: T)
        fun getName(): String
    }

    public companion object {

        /** Responds 403. Use when [parse] returns [ParseResult.Forbidden]. */
        public suspend fun respondForbidden(call: ApplicationCall) {
            call.respondHtml(status = HttpStatusCode.Forbidden) {
                body { +"The request could not be verified. Please reload the page and try again." }
            }
        }

        /** Responds with the problems. Use when [parse] returns [ParseResult.Invalid]. */
        public suspend fun respondInvalid(result: ParseResult.Invalid<*>, call: ApplicationCall) {
            if (call.request.queryParameters["onlyErrors"]?.equals("true") == true) {
                call.respond(HttpStatusCode.BadRequest, createBody(result.problems))
            } else {
                call.respondHtml {
                    body {
                        h1 { +"Problem" }
                        +(result.problems.first().toString())
                        form {
                            button {
                                onClick = "history.back();"
                                +"Back"
                            }
                        }
                    }
                }
            }
        }

        private fun createBody(problems: Set<Problem>): String {
            /*            val responses = mutableListOf<ValidationProblemResponse>()
                        val fieldsMustBeNull =
                            problems.filter { it.fieldsMustBeNull != null }
                                .flatMap { it.fieldsMustBeNull ?: emptySet() }
                                .map { it.name }.toSet()
                        val fieldsMustNotBeNull = problems.filter { it.fieldsMustNotBeNull != null }
                            .flatMap { it.fieldsMustNotBeNull ?: emptySet() }.map { it.name }.toSet()
                        for (problem in problems) {
                            if (problem.fieldsMustNotBeNull == null && problem.fieldsMustBeNull == null) {
                                // We can't do anything but show the problem to the user
                                responses.add(
                                    ValidationProblemResponse(humanReadable = problem.toString(), field = null),
                                )
                            }
                        }

                        val response = ValResponse(
                            problems = responses,
                            fieldsMustBeNull = fieldsMustBeNull,
                            fieldsMustNotBeNull = fieldsMustNotBeNull
                        )
             */
            val fieldProblems = problems
                .filterIsInstance<InvalidPropertyProblem>()
                .associate { p -> Pair(p.propertyName, p.endUserTranslatedMessage) }
                .map { ValidationProblemResponse(it.key, it.value) }

            val collectionProblems = problems
                .filterIsInstance<InvalidPropertyCollectionProblem>()
                .map { it.endUserTranslatedMessage }

            val response = ValResponse(fieldProblems, collectionProblems, emptySet(), emptySet())

            return Json.encodeToString(ValResponse.serializer(), response)
        }
    }
}

/**
 * The blob a file field stands in with while a form is being validated.
 *
 * The validation script submits the form on every change, as a dry run. Doing the real work then would upload the
 * file a second time and consume the upload the form still points at, so a dry run leaves the file alone and the
 * property gets an id that refers to nothing - enough to build the parameters and check every other field.
 *
 * A blob property validates to nothing on its own, so this is invisible - except to [respondDryRun], which issues the
 * command: that reports the placeholder as attached data that does not exist. Respond to
 * [ParseResult.DryRun] without issuing the command when the form has a file field.
 */
private val DRY_RUN_BLOB = AttachedBlobID(0)

/**
 * Puts a resolved blob into the parameters a form submission produced.
 *
 * `toString` is the id, which is all klerk-web is allowed to see of a blob. The null-toggle is what the other inputs
 * use to say "this nullable field has a value": without it, a nullable parameter is read as null no matter what was
 * submitted, and a chosen file would silently not be attached. There is no visible toggle for a file field — choosing
 * a file is the toggle.
 */
private fun ParametersBuilder.appendBlob(name: String, blob: AttachedBlobID) {
    append(name, blob.toString())
    append("null-toggle-$name", "on")
}

/** A field left out of the submission gets its default value, if it has one. */
internal fun createParamClassFromCallParameters(schema: ObjectSchema<*>, callParams: Parameters): Any {
    val values = mutableMapOf<String, Any?>()
    for (field in schema.fields) {
        // A nullable reference (renderReferenceSelect) or enum (renderEnumSelect) is a <select> with its own
        // "(none)" option, not a renderInput() field -- so unlike those, it never gets a null-toggle checkbox.
        // Without this check, every nullable reference/enum select was silently forced to null on submit,
        // regardless of what the user picked, since callParams[nullToggleKey] is always absent for them.
        val isReferenceOrEnumSelect = field.type == PropertyType.Ref || field.type == PropertyType.Enum
        val isNullToggled = callParams["null-toggle-${field.name}"] != "on"
        if (!isReferenceOrEnumSelect && isNullToggled && field.isNullable) {
            values[field.name] = null
        } else {
            val value = valueWithCorrectType(callParams[field.name], field)
            if (value != null || field.isNullable) {
                values[field.name] = value
            }
        }
    }
    return schema.create(values)
}

/** Thrown while reading a submission whose file the application's rules, or the property's limit, refuse. */
internal class UploadRefused(message: String) : RuntimeException(message)

/**
 * Reads at most [limit] bytes and then refuses.
 *
 * A file arriving with the form has no declared length to check in advance, so the limit is enforced while copying:
 * the alternative is storing whatever was sent and discovering at claim time that it was far too large.
 */
private class LimitedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {

    private var read = 0L

    override fun read(): Int {
        val b = source.read()
        if (b != -1 && ++read > limit) {
            throw UploadRefused("The file is larger than the $limit bytes this field allows")
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = source.read(b, off, len)
        if (count > 0) {
            read += count
            if (read > limit) {
                throw UploadRefused("The file is larger than the $limit bytes this field allows")
            }
        }
        return count
    }

    override fun close() {
        source.close()
    }
}

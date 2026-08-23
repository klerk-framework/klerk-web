package dev.klerkframework.web

import com.google.gson.Gson
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.misc.EventParameter
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.misc.extractNameFromFunction
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.validation.PropertyValidation
import dev.klerkframework.web.assets.JsAsset
import dev.klerkframework.web.assets.formJs
import dev.klerkframework.web.assets.uploadJs
import dev.klerkframework.web.upload.Upload
import dev.klerkframework.web.upload.UploadPlugin
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import dev.klerkframework.web.assets.klerkFormValidationJsFile
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.format
import kotlinx.html.*
import kotlinx.html.InputType.*
import mu.KotlinLogging
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.io.InputStream
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.Duration.Companion.minutes

private val fileLog = KotlinLogging.logger {}

private val CSRF_TOKEN = Csrf.TOKEN_NAME
/** How long a blob prepared from an upload waits for its command. Long enough to fix a form and submit again. */
private val UPLOAD_BLOB_LEASE = 15.minutes

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
    init: FormTemplate<T, C, V>.() -> Unit
) {
    private val log = KotlinLogging.logger {}

    internal val parameters: EventParameters<T> = defaultValues.parameters

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

    public fun text(property: KProperty1<*, StringContainer?>): Unit {
        inputs.add(Pair(property.name, text))
    }

    private fun text(parameter: EventParameter) = inputs.add(Pair(parameter.name, text))

    public fun email(property: KProperty1<*, StringContainer?>): Unit {
        inputs.add(Pair(property.name, email))
    }

    private fun email(parameter: EventParameter) =
        inputs.add(Pair(parameter.name, email)) // use <input type="text" inputmode="email"> instead?

    public fun password(property: KProperty1<*, StringContainer?>): Unit {
        inputs.add(Pair(property.name, password))
    }

    private fun password(parameter: EventParameter) = inputs.add(Pair(parameter.name, password))

    public fun number(property: KProperty1<*, DataContainer<*>?>): Unit {
        inputs.add(Pair(property.name, number))
    }

    private fun number(parameter: EventParameter) = inputs.add(Pair(parameter.name, number))

    public fun checkbox(property: KProperty1<*, BooleanContainer?>): Unit {
        inputs.add(Pair(property.name, checkBox))
    }

    private fun checkbox(parameter: EventParameter) = inputs.add(Pair(parameter.name, checkBox))

    /** A `datetime-local` input. The value is interpreted in the browser's time zone. */
    public fun dateTime(property: KProperty1<*, InstantContainer?>): Unit {
        inputs.add(Pair(property.name, dateTimeLocal))
    }

    private fun dateTime(parameter: EventParameter) = inputs.add(Pair(parameter.name, dateTimeLocal))

    /** A number of seconds. */
    public fun duration(property: KProperty1<*, DurationContainer?>): Unit {
        inputs.add(Pair(property.name, number))
    }

    private fun duration(parameter: EventParameter) = inputs.add(Pair(parameter.name, number))

    public fun hidden(property: KProperty1<*, Any?>): Unit {
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
    public fun file(property: KProperty1<*, AttachedBlobContainer?>): Unit {
        fileInputs.add(property.name)
    }

    /**
     * The [AttachedBlobContainer] class the property holds, which is what `prepare` needs in order to know what the file must
     * be and what has to happen to it first.
     *
     * [blobDeclarationFor] answers "what does this property declare"; this answers "which declaration is it".
     */
    @Suppress("UNCHECKED_CAST")
    internal fun blobClassFor(propertyName: String): KClass<out AttachedBlobContainer> {
        val kClass = parameters.raw.declaredMemberProperties
            .singleOrNull { it.name == propertyName }
            ?.returnType?.jvmErasure
        require(kClass != null && kClass.isSubclassOf(AttachedBlobContainer::class)) {
            "file('$propertyName') needs a parameter of an AttachedBlobContainer type, but ${parameters.raw.simpleName}." +
                    "$propertyName is ${kClass?.simpleName ?: "not a parameter at all"}"
        }
        return kClass as KClass<out AttachedBlobContainer>
    }

    /**
     * The [AttachedBlobContainer] declared for [propertyName], so that the form can render what it says.
     *
     * The blob id it is given is a placeholder: what is wanted here is the declaration, not a particular value.
     */
    internal fun blobDeclarationFor(propertyName: String): AttachedBlobContainer? {
        val kClass = parameters.raw.declaredMemberProperties
            .singleOrNull { it.name == propertyName }
            ?.returnType?.jvmErasure ?: return null
        if (!kClass.isSubclassOf(AttachedBlobContainer::class)) {
            return null
        }
        return runCatching {
            kClass.constructors.single { c -> c.parameters.size == 1 }.call(AttachedBlobID(0)) as AttachedBlobContainer
        }.onFailure { e -> log.warn(e) { "Could not read the declaration of $propertyName" } }.getOrNull()
    }

    public fun selectReference(property: KProperty1<*, ModelID<out Any>?>): Unit {
        selectReferences.add(property.name)
    }

    private fun selectReference(parameter: EventParameter) = selectReferences.add(parameter.name)

    private fun selectEnum(parameter: EventParameter) = selectEnums.add(parameter.name)

    /*    fun selectEnum(property: KProperty1<*, EnumContainer<*>>) = selectEnums.add(property.name)
        private fun selectEnum(parameter: EventParameter) = selectEnums.add(parameter.name)
     */

    public fun populatedAfterSubmit(property: KProperty1<*, Any?>): Unit {
        propsPopulatedAfterSubmit.add(property.name)
    }

    public fun remaining(inHtmlDetails: String? = null): Unit {
        htmlDetailsSummary = inHtmlDetails
        val remaining = parameters.all
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
                    PropertyType.Int -> number(p)
                    PropertyType.Long -> number(p)
                    PropertyType.Float -> number(p)
                    PropertyType.Ref -> selectReference(p)
                    PropertyType.Enum -> selectEnum(p)
                    PropertyType.Instant -> dateTime(p)
                    PropertyType.Duration -> duration(p)
                    PropertyType.AttachedDataRef -> throw IllegalStateException(
                        "The property '${p.name}' refers to attached data. Declare it with file() if it is an " +
                                "AttachedBlobID the user should upload, or with hidden()/populatedAfterSubmit() if " +
                                "your own code supplies it. remaining() never renders one by itself, since uploading " +
                                "requires the Uploads plugin."
                    )

                    else -> throw IllegalStateException(
                        "klerk-web cannot render the property '${p.name}' of type '${p.type?.name ?: "unknown"}'." +
                                " If you are writing the form yourself, declare that property with hidden() or" +
                                " populatedAfterSubmit() instead of leaving it to remaining()."
                    )
                }
            }
    }

    public fun build(
        call: ApplicationCall,
        params: T?,
        reader: Reader<C, *>,
        modelIDSelects: Map<KProperty1<*, ModelID<out Any>?>, ModelView<out Any, C>> = emptyMap(),
        // enumSelects: Map<KProperty1<*, EnumContainer<*>>, Array<out Enum<*>>>? = null,
        path: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        translator: Translation,
        context: C
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
        parameters.all
            .filter { selectEnums.contains(it.name) }
            .forEach { eventParameter ->
                val validEnums =
                    klerk.spec.getValidEnumsFor(defaultValues.eventReference, eventParameter) ?: getEnumEntries(
                        eventParameter.valueClass.supertypes.first { it.classifier == EnumContainer::class }.arguments.first()
                    )
                result.add(EnumPropertyWithOptions(eventParameter.name, eventParameter.isNullable, validEnums))
            }
        return result
    }

    private fun getEnumEntries(projection: KTypeProjection): Set<Enum<*>>  {
        val kType = projection.type ?: error("Projection has no type")
        val kClass = kType.classifier as? KClass<*> ?: error("Not a class type")
        if (!kClass.java.isEnum) {
            error("Type is not an enum")
        }
        return kClass.java.enumConstants.toSet() as Set<Enum<*>>
    }

    private fun populateMissingReferenceSelects(
        developerProvidedModelIDSelects: Map<KProperty1<*, ModelID<out Any>?>, ModelView<out Any, C>>,
        reader: Reader<C, *>,
        inputs: List<Pair<String, InputType>>,
        context: C,
    ): Set<ReferencePropertyWithOptions> {
        val result = mutableSetOf<ReferencePropertyWithOptions>()
        parameters.all
            .filter { it.raw.type.withNullability(false).isSubtypeOf(ModelID::class.starProjectedType) }
            .map { eventParameter ->
                val ls = klerk.spec.getValidationCollectionFor(defaultValues.eventReference, eventParameter)
                    ?: return@map
                val options = reader.query(ls, QueryOptions(maxItems = 300)).items
                if (options.size >= 300) {
                    TODO("Too many options")
                } else {
                    // suggestedEvents can be used if there is a need to first create a model that is then used in this event.
                    val suggestedEvents = if (options.isNotEmpty()) emptyList() else klerk.spec.getPossibleVoidEvents(Class.forName(eventParameter.modelIDType).kotlin, context)
                    result.add(ReferencePropertyWithOptions(eventParameter.name, eventParameter.isNullable, options, suggestedEvents))
                }
            }

        result.addAll(developerProvidedModelIDSelects.map { entry ->
            ReferencePropertyWithOptions(
                entry.key.name, entry.key.returnType.isMarkedNullable,
                reader.query(entry.value).items,
                emptyList(),
            )
        }
        )
        return result
    }

    internal fun validate() {
        if (klerk.spec.getParameters(defaultValues.eventReference) != defaultValues.parameters) {
            log.warn { "Trying to make a form for an event that doesn't match the parameters" }
        }

        parameters.all.forEach {
            it.validate()
        }

        if (fileInputs.isNotEmpty() && uploads == null) {
            throw IllegalStateException(
                "The form declares file(${fileInputs.joinToString(", ")}) but has no 'uploads' plugin. Pass the " +
                        "UploadPlugin to the FormTemplate, and register its routes."
            )
        }

        val missing = parameters.all
            .filterNot { prop -> inputs.map { i -> i.first }.contains(prop.name) }
            .filterNot { prop -> selectReferences.contains(prop.name) }
            .filterNot { prop -> selectEnums.contains(prop.name) }
            .filterNot { prop -> propsPopulatedAfterSubmit.contains(prop.name) }
            .filterNot { prop -> fileInputs.contains(prop.name) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Form for class ${parameters.raw} is missing declaration for ${
                    missing.map { it.name }.joinToString(", ")
                }"
            )
        }
        val tooMany = inputs        // also selects and other stuff...
            .map { i -> i.first }
            .filterNot { i -> parameters.all.map { it.name }.contains(i) }
        if (tooMany.isNotEmpty()) {
            throw IllegalStateException(
                "Form for class ${parameters.raw} contains too many declarations: ${
                    tooMany.joinToString(
                        ", "
                    )
                }"
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
     * @param context the caller's context. Used to translate the messages the user will read.
     */
    public suspend fun parse(
        call: ApplicationCall,
        context: C,
        populatedAfterSubmit: Map<KProperty1<*, Any?>, DataContainer<*>> = emptyMap(),      // not only DataContainer, also references. Collections?
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
                return ParseResult.Invalid(setOf(BadRequestProblem(e.message ?: "The file was refused", KlerkErrorCode.NotFound)))
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
        val key = callParams[IDEMPOTENCE_KEY]?.let { CommandToken.from(it) }
            ?: throw IllegalArgumentException("Missing input: $IDEMPOTENCE_KEY")

        callParams.forEach { name, _ ->
            if (name != CSRF_TOKEN && name != IDEMPOTENCE_KEY && inputs.none { it.first == name } && selectReferences.none { it == name } && selectEnums.none { it == name } && fileInputs.none { it == name } && !name.startsWith(
                    "null-toggle-"
                )) {
                throw IllegalArgumentException("Parameter $name is not expected to be present in request")
            }
        }

        val allParams = callParams.plus(ParametersBuilder().apply {
            populatedAfterSubmit.forEach { p -> append(p.key.name, p.value.valueWithoutAuthorization.toString()) }
        }.build())


        // someday maybe: https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#verifying-origin-with-standard-headers
        try {
            @Suppress("UNCHECKED_CAST")
            val paramsClass = createParamClassFromCallParameters(parameters.raw, allParams) as T

            // Level 1: each property on its own.
            val propertyValidationProblems = mutableSetOf<InvalidPropertyProblem>()
            paramsClass::class.memberProperties.forEach { property ->
                if (property.returnType.isSubtypeOf(DataContainer::class.starProjectedType)) {
                    val problem =
                        (property.getter.call(paramsClass) as DataContainer<*>).validate(property.name, context.translation)
                    if (problem != null) {
                        propertyValidationProblems.add(problem)
                    }
                }
            }

            if (propertyValidationProblems.isNotEmpty()) {
                return ParseResult.Invalid(propertyValidationProblems.toSet())
            }

            // Level 2: the properties together.
            val validationProblems: MutableSet<PropertyCollectionValidity.Invalid> = if (paramsClass is Validatable) {
                paramsClass.validators()
                    .mapNotNull {
                         val result = it.invoke()
                         return@mapNotNull if (result is PropertyCollectionValidity.Invalid) {
                             if (result.endUserTranslatedMessage != null) result else PropertyCollectionValidity.Invalid(extractNameFromFunction(it))
                         } else {
                             null
                         }
                    }.toMutableSet()
            } else mutableSetOf()

            if (validationProblems.isNotEmpty()) {
                return ParseResult.Invalid(validationProblems.map { it.toProblem() }.toSet())
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
                        KlerkErrorCode.Internal
                    )
                )
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
     * @param isDryRun a dry run stands the file in with [DRY_RUN_BLOB] instead, leaving the upload untouched.
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
                values.forEach { builder.append(name, it) }
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
     * @param isDryRun a dry run stands the file in with [DRY_RUN_BLOB] instead, so nothing is stored. The
     * application's own upload rule is still consulted, since that is what the user is asking about.
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
                    if (authorized && name != null && fileInputs.contains(name) &&
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
                            } catch (e: BlobRejected) {
                                // A step said no. Same treatment as a size refusal: the body is still drained, and
                                // the user is told why.
                                refused = e.message
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
            submittedUploads.forEach { (name, uploadId) ->
                // A field that also arrived as bytes has already been dealt with; the bytes win.
                val id = uploadId.toIntOrNull()?.takeIf { !fromFileParts.contains(name) } ?: return@forEach
                if (isDryRun) {
                    builder.appendBlob(name, DRY_RUN_BLOB)
                    return@forEach
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

    public fun labelProvider(labelProvider: (UIElementData) -> String?) {
        this.labelProvider = labelProvider
    }

    private class TextInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, StringContainer?>,
        val labelText: String,
        val datatypeValue: StringContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //val initialValue = params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as StringValue

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.string
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

    private class IntInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: IntContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //val initialValue = (params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as Datatype<*>).value.toString()

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.int.toString()
                required = !property.returnType.isMarkedNullable
                datatypeValue.min?.apply { min = this.toString() }
                datatypeValue.max?.apply { max = this.toString() }
            }
        }

        override fun getName() = property.name
    }

    private class LongInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: LongContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //val initialValue = (params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as Datatype<*>).value.toString()

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = datatypeValue.long.toString()
                required = !property.returnType.isMarkedNullable
                datatypeValue.min?.apply { min = this.toString() }
                datatypeValue.max?.apply { max = this.toString() }
            }
        }

        override fun getName() = property.name
    }

    private class CheckboxInputItem<T>(
        val type: InputType,
        val property: KProperty1<*, DataContainer<*>?>,
        val labelText: String,
        val datatypeValue: BooleanContainer
    ) : Item<T> {
        override fun render(tag: HtmlBlockTag, params: T) {
            //  val initialValue = params::class.memberProperties.single { it.name.equals(property.name) }.getter.call(params) as BooleanValue

            tag.label {
                htmlFor = property.name
                +labelText
            }
            tag.input(type) {
                id = property.name
                name = property.name
                value = "true"
                checked = datatypeValue.boolean
                //  required = !property.returnType.isMarkedNullable
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
        val minFunction: (() -> LocalDate)?
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
                    min = minFunction.invoke().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
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
        val columns: Int = 30
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
                        problems.forEach {
                            if (it.fieldsMustNotBeNull == null && it.fieldsMustBeNull == null) {
                                // We can't do anything but show the problem to the user
                                responses.add(ValidationProblemResponse(humanReadable = it.toString(), field = null))
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

            return Gson().toJson(response)
        }
    }
}

internal data class ValResponse(
    val propertyProblems: List<ValidationProblemResponse>,
    val propertyCollectionProblems: List<String>,
    val fieldsMustBeNull: Set<String>,
    val fieldsMustNotBeNull: Set<String>
)

/** One problem in a validation response. [field] is null when the problem is not tied to a single input. */
public data class ValidationProblemResponse(public val field: String?, public val humanReadable: String)

/** A form built from a [FormTemplate], ready to be rendered. Several may be rendered on the same page. */
public class EventForm<T : Any, C : KlerkContext, V>(
    private val csrfToken: String,
    private val inputs: List<Pair<String, InputType>>,
    private val referenceSelects: Set<ReferencePropertyWithOptions>,
    private val enumSelects: Set<EnumPropertyWithOptions>,
    private val propsPopulatedAfterSubmit: List<String>,
    private val fileInputs: List<String>,
    private val params: T?,
    private val postPath: String?,
    private val queryParams: Map<String, String>,
    private val htmlDetailsSummary: String?,
    private val htmlDetailsContents: Set<String>,
    private val template: FormTemplate<T, C, V>,
    private val translator: Translation,
    private val context: C,
    private val currentUri: String,
    ) {
    private val log = KotlinLogging.logger {}

    /**
     * Every id is prefixed with this so that several forms can be rendered on the same page without colliding.
     * The JavaScript never looks an element up globally; it scopes every lookup to the form element.
     */
    private val formId: String = "klerk-form-${generateRandomString().take(10)}"

    private fun elementId(propertyName: String): String = "$formId-$propertyName"
    private fun labelId(propertyName: String): String = "$formId-label-$propertyName"
    private fun errorId(propertyName: String): String = "$formId-error-$propertyName"

    /**
     * A file input plus the hidden field that carries the upload's id.
     *
     * The script uploads the chosen file to the plugin's routes and fills in the hidden field; if it never runs, the
     * file input posts with the form instead and the server does the upload in one go. The `name` is on the hidden
     * field either way, so the parameter is called the same thing whichever path was taken — except without
     * JavaScript, where the file input has to carry the name itself.
     */
    private fun renderFileInput(propertyName: String): FlowContent.() -> Unit = {
        label {
            id = labelId(propertyName)
            attributes["data-label-for"] = propertyName
            htmlFor = elementId(propertyName)
            val property = template.parameters.raw.declaredMemberProperties.single { it.name == propertyName }
            +(template.labelProvider?.invoke(
                UIElementData(propertyName, dummyContainerFor(propertyName), true)
            ) ?: translator.klerk.property(property))
        }
        val declaration = template.blobDeclarationFor(propertyName)
        input(InputType.file) {
            id = elementId(propertyName)
            // Named so that a submit without JavaScript still carries the file. The script removes the name when it
            // takes over, so the bytes are not sent twice.
            name = propertyName
            attributes["data-klerk-file"] = propertyName
            required = !template.parameters.all.single { it.name == propertyName }.isNullable
            // Derived from the property's AttachedBlobContainer, the same way maxlength is derived from a StringContainer.
            // It only helps the user pick the right file; what actually keeps a wrong one out is the check the
            // command makes against the bytes.
            declaration?.accept?.takeIf { it.isNotEmpty() }?.let { accept = it.sorted().joinToString(",") }
            declaration?.maxSize?.takeIf { it != Long.MAX_VALUE }
                ?.let { attributes["data-klerk-max-size"] = it.toString() }
        }
        hiddenInput {
            id = "${elementId(propertyName)}-upload"
            attributes["data-klerk-upload-id"] = propertyName
        }
        span(classes = "upload-progress") {
            id = "${elementId(propertyName)}-progress"
            attributes["data-klerk-upload-progress"] = propertyName
        }
    }

    private fun dummyContainerFor(propertyName: String): DataContainer<*> =
        template.parameters.all.single { it.name == propertyName }.getDummyInstance()

    private fun renderReferenceSelect(prop: ReferencePropertyWithOptions, params: T?): FlowContent.() -> Unit = {
        label {
            id = labelId(prop.propertyName)
            attributes["data-label-for"] = prop.propertyName
            htmlFor = elementId(prop.propertyName)
            /*    if (!enabled) {
                    style = "opacity: 0.5;"
                }
             */
            +camelCaseToPretty(prop.propertyName)
        }
        br()
        select {
            id = elementId(prop.propertyName)
            name = prop.propertyName
            if (prop.propertyNullable) {
                option {
                    value = ""
                    +"(none)"
                }
            }
            optGroup() {
                prop.options.forEach { option ->
                    option {
                        value = option.id.toString()
                        params?.let {
                            val paramValue = getModelIdValue(prop.propertyName, params)
                            selected = paramValue == option.id.value
                        }
                        +option.toString()
                    }
                }
            }
        }
    }
    private fun renderEnumSelect(prop: EnumPropertyWithOptions, params: T?): FlowContent.() -> Unit = {
        label {
            id = labelId(prop.propertyName)
            attributes["data-label-for"] = prop.propertyName
            htmlFor = elementId(prop.propertyName)
            +camelCaseToPretty(prop.propertyName)
        }
        br()
        select {
            id = elementId(prop.propertyName)
            name = prop.propertyName
            if (prop.propertyNullable) {
                option {
                    value = ""
                    +"(none)"
                }
            }
            optGroup() {
                prop.options.forEach { enumValue ->
                    option {
                        value = enumValue.name
                        params?.let {
                            val paramValue = getEnumValue(prop.propertyName, params)
                            selected = paramValue == enumValue.name
                        }
                        +enumValue.toString()
                    }
                }
            }
        }
    }

    private fun renderInput(
        propertyName: String,
        type: InputType,
        parameters: EventParameters<T>,
        params: T?,
        classProvider: CssClassProvider?,
    ): FlowContent.() -> Unit =
        {
            val isNullable = parameters.all.single { it.name == propertyName }.isNullable
            val value: DataContainer<*> = if (params == null) {
                val prop = parameters.all.single { it.name == propertyName }
                prop.recommendedDefaultValue?.let { prop.getInstance(it) } ?: prop.getDummyInstance()
            } else {
                getParamDatatype(propertyName, params)
            }

            if (isNullable && type != InputType.hidden) {
                apply(renderNullableToggle(propertyName, (params == null || value != null)))
            }
            when (type) {
                text -> this.apply(
                    renderTextInput(
                        propertyName,
                        value,
                        text,
                        classProvider.attr(UiPart.Form, "input", propertyName)
                    )
                )

                email -> this.apply(
                    renderTextInput(
                        propertyName,
                        value,
                        email,
                        classProvider.attr(UiPart.Form, "input", propertyName)
                    )
                )

                password -> this.apply(
                    renderTextInput(
                        propertyName,
                        value,
                        password,
                        classProvider.attr(UiPart.Form, "input", propertyName)
                    )
                )

                number -> {
                    when (value) {
                        is IntContainer -> this.apply(renderIntNumberInput(propertyName, value))
                        is LongContainer -> this.apply(renderLongNumberInput(propertyName, value))
                        is FloatContainer -> this.apply(renderFloatNumberInput(propertyName, value))
                        is DurationContainer -> this.apply(renderDurationInput(propertyName, value))
                        else -> throw IllegalStateException(
                            "Cannot render '$propertyName' as a number: it is a ${value::class.simpleName}"
                        )
                    }
                }

                dateTimeLocal -> this.apply(renderInstantInput(propertyName, value as InstantContainer))

                checkBox -> this.apply(
                    renderCheckboxInput(
                        propertyName,
                        value,
                    )
                )

                InputType.hidden -> {
                    requireNotNull(params) { "Params cannot be null when there are hidden inputs" }
                    val modelId = getModelId(propertyName, params)
                    val valueAsString: String? =
                        modelId?.toString() ?: getParamDatatype(propertyName, params)?.toString()
                    this.apply(renderHiddenInput(propertyName, valueAsString))
                }

                else -> TODO(type.name)
            }
        }

    private fun getModelId(propertyName: String, params: T): ModelID<*>? {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return try {
            prop.getter.call(params) as ModelID<*>
        } catch (e: Exception) {
            null
        }
    }

    private fun createLabel(
        propertyName: String,
        //typeInstance: DataContainer<*>,
        //enabled: Boolean
    ): FlowContent.() -> Unit = {
        //val elementData = UIElementData(propertyName, typeInstance, enabled)
        val description = translator.klerk.propertyDescription(propertyName)
        if (description != null) {
            label(classes = "tooltip") {
                attributes["data-description"] = description
                id = labelId(propertyName)
                attributes["data-label-for"] = propertyName
                htmlFor = elementId(propertyName)
                //if (!enabled) {
                //  style = "opacity: 0.5;"
                //}
                //  +(template.labelProvider?.invoke(elementData) ?: camelCaseToPretty(propertyName))
//            +translator.translateProperty(propertyName)
                val property = template.parameters.raw.declaredMemberProperties.single { it.name == propertyName }
                +translator.klerk.property(property)
                //+camelCaseToPretty(propertyName)
                }
            } else {

        label {
            id = labelId(propertyName)
            attributes["data-label-for"] = propertyName
            htmlFor = elementId(propertyName)
            //if (!enabled) {
            //  style = "opacity: 0.5;"
            //}
            //  +(template.labelProvider?.invoke(elementData) ?: camelCaseToPretty(propertyName))
//            +translator.translateProperty(propertyName)
            val property = template.parameters.raw.declaredMemberProperties.single { it.name == propertyName }
            +translator.klerk.property(property)
            //+camelCaseToPretty(propertyName)
        }
            }
        //   br()
    }

    private fun createErrorPlaceholder(propertyName: String): FlowContent.() -> Unit = {
        span(classes = "input-error-message") {
            style = "visibility: hidden; min-height: 1.2em; display: inline-block; padding-left: 10px;"
            id = errorId(propertyName)
            attributes["data-error-for"] = propertyName
            role = "alert"
            +""
        }
    }

    private fun renderNullableToggle(
        propertyName: String,
        enabled: Boolean,
    ): FlowContent.() -> Unit = {
        val checkboxName = "null-toggle-$propertyName"
        input(checkBox) {
            id = elementId(checkboxName)
            name = checkboxName
            value = "on"
            checked = enabled
            autoComplete = "off"
            attributes["onchange"] = """let e = document.getElementById('${elementId(propertyName)}'); e.style.display = this.checked ? null : "none"; e.disabled = !this.checked;"""
        }
    }

    private fun renderHiddenInput(propertyName: String, theValue: String?): FlowContent.() -> Unit = {
        input(InputType.hidden) {
            id = elementId(propertyName)
            name = propertyName
            theValue?.let {
                value = theValue
            }
        }
    }

    private fun renderCheckboxInput(
        propertyName: String,
        theValue: DataContainer<*>,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        theValue as BooleanContainer
        input(checkBox) {
            id = elementId(propertyName)
            name = propertyName
            value = "true"
            checked = theValue.boolean
            //  disabled = theValue == null
        }
        input(InputType.hidden) {
            name = propertyName
            value = "false"
        }
        // required = !property.returnType.isMarkedNullable
    }

    private fun renderIntNumberInput(
        propertyName: String,
        theValue: IntContainer,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(number) {     // perhaps use `type=text inputmode=numeric` instead?
            id = elementId(propertyName)
            name = propertyName
            value = theValue.toString()
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
            // required = !property.returnType.isMarkedNullable
            theValue.min?.apply { min = this.toString() }
            theValue.max?.apply { max = this.toString() }
        }
        apply(createErrorPlaceholder(propertyName))
    }


    private fun renderLongNumberInput(
        propertyName: String,
        theValue: LongContainer,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(number) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.toString()
            // required = !property.returnType.isMarkedNullable
            theValue.min?.apply { min = this.toString() }
            theValue.max?.apply { max = this.toString() }
        }
    }

    private fun renderFloatNumberInput(
        propertyName: String,
        theValue: FloatContainer,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(number) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.toString()
            // required = !property.returnType.isMarkedNullable
            theValue.min?.apply { min = this.toString() }
            theValue.max?.apply { max = this.toString() }
            step = "any"
        }
    }

    /**
     * A `datetime-local` input. The browser sends local time without a zone, so the submitted value is interpreted
     * in the server's time zone (see valueWithCorrectType).
     */
    private fun renderInstantInput(
        propertyName: String,
        theValue: InstantContainer,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(InputType.dateTimeLocal) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.instant.toLocalDateTime(TimeZone.currentSystemDefault()).format(dateTimeLocalFormat)
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
        }
        apply(createErrorPlaceholder(propertyName))
    }

    /** A duration, expressed as a number of seconds. */
    private fun renderDurationInput(
        propertyName: String,
        theValue: DurationContainer,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(number) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.duration.inWholeSeconds.toString()
            step = "1"
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
        }
        +" seconds"
        apply(createErrorPlaceholder(propertyName))
    }

    private fun renderTextInput(
        propertyName: String,
        theValue: DataContainer<*>,
        type: InputType,
        classes: String?,
    ): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        theValue as StringContainer
        input(type, classes = classes) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.string
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
            //
            // disabled = theValue == null
            theValue.minLength?.let {
                minLength = it.toString()
                required = it > 0
            }
            theValue.maxLength?.let { maxLength = it.toString() }
            if (theValue.regexPattern != null) {
                pattern = theValue.regexPattern!!.toString()
            } // for some reason, the apply didn't work
        }
        apply(createErrorPlaceholder(propertyName))
    }

    private fun getParamDatatype(propertyName: String, params: T): DataContainer<*> {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return prop.getter.call(params) as DataContainer<*>
    }

    private fun getModelIdValue(propertyName: String, params: T): Int? {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return (prop.getter.call(params) as? ModelID<*>)?.value
    }

    private fun getEnumValue(propertyName: String, params: T): String? {
        val prop = params::class.memberProperties.single { it.name == propertyName }
        return (prop.getter.call(params) as? Enum<*>)?.name
    }

    internal fun renderInto(tag: FlowContent, postPath: String? = null) {
        val emptyNonNullableReferenceSelects = referenceSelects.filter { !it.propertyNullable && it.options.isEmpty() }
        if (emptyNonNullableReferenceSelects.isNotEmpty()) {
            tag.p {
                +"${template.defaultValues.eventReference.eventName} is not possible since there are no options available for the required field(s): ${
                    emptyNonNullableReferenceSelects.joinToString(
                        ", "
                    ) { it.propertyName }
                }"
            }

            emptyNonNullableReferenceSelects.forEach {
                it.suggestedEvents.forEach { event ->
                    template.autoButtons?.let { ab ->
                        tag.p {
                            with(ab.support) {
                                eventButton(event, null, context, onCancelPath = currentUri, onSuccessAndModelExistPath = currentUri)
                            }
                        }
                    }
                }
            }
            return
        }
        val path = getPath(postPath, queryParams)
        tag.script {
            src = template.pathProvider.assetPath(formJs.getPathAndHash()) // "${template.pathProvider.assetsBase}/$klerkFormValidationJsFile"
            defer = true
        }
        if (fileInputs.isNotEmpty()) {
            tag.script {
                src = template.pathProvider.assetPath(uploadJs.getPathAndHash())
                defer = true
            }
        }
        // multipart only when there is a file to send: it is the encoding a submit without JavaScript needs, and
        // costs nothing when the script has already uploaded the bytes and cleared the file input's name.
        val encoding = if (fileInputs.isEmpty()) null else FormEncType.multipartFormData
        tag.form(path, method = FormMethod.post, encType = encoding) {
                id = formId
                // The script binds itself to every form carrying this attribute, so no global handler is needed.
                attributes["data-klerk-form"] = "true"
                if (fileInputs.isNotEmpty()) {
                    attributes["data-klerk-upload-path"] = template.uploads?.mountedAt
                        ?: error("The form has a file field but the uploads plugin has no routes registered")
                    // Lets the upload endpoint apply the property's own limit before accepting any bytes.
                    attributes["data-klerk-event"] = template.defaultValues.eventReference.id()
                }
                +System.lineSeparator()

                // csrf-token should be placed before non-hidden inputs (see https://portswigger.net/web-security/csrf/preventing#how-should-csrf-tokens-be-transmitted)
                hiddenInput(name = CSRF_TOKEN) { value = csrfToken }

                +System.lineSeparator()
                hiddenInput(name = IDEMPOTENCE_KEY) { value = CommandToken.simple().toString() }
                +System.lineSeparator()
                inputs.filterNot { htmlDetailsContents.contains(it.first) }.forEach { (propertyName, type) ->
                    p {
                        tag.apply(
                            renderInput(
                                propertyName,
                                type,
                                template.parameters,
                                params,
                                template.classProvider
                            )
                        )
                    }
                    +System.lineSeparator()
                }
                fileInputs.forEach { propertyName ->
                    p { tag.apply(renderFileInput(propertyName)) }
                    +System.lineSeparator()
                }
                referenceSelects.forEach { refSelect ->
                    p { tag.apply(renderReferenceSelect(refSelect, params)) }
                }
                enumSelects.forEach { enumSelect ->
                    p { tag.apply(renderEnumSelect(enumSelect, params)) }
                }
                if (htmlDetailsContents.isNotEmpty()) {
                    details {
                        summary { +(htmlDetailsSummary ?: "Details") }
                        inputs.filter { htmlDetailsContents.contains(it.first) }.forEach {
                            tag.apply(
                                renderInput(
                                    it.first,
                                    it.second,
                                    template.parameters,
                                    params,
                                    template.classProvider
                                )
                            )
                        }
                    }
                }
                p {
                    span(classes = "errormessages") {
                        id = "$formId-errormessages"
                        attributes["data-klerk-errormessages"] = "true"
                        role = "alert"
                        attributesMapOf(key = "aria-live", value = "assertive")
                    }
                }

                submitInput {
                    value = "Ok"
                    id = "$formId-submit"
                    attributes["data-klerk-submit"] = "true"
                }
            }
    }

    private fun getPath(postPath: String?, queryParams: Map<String, String>): String {
        var path = requireNotNull(
            postPath ?: this.postPath
        ) { "postPath must be provided, either when building the form or when rendering the form" }
        if (path.contains("?")) {
            path += "&"
        } else {
            path += "?"
        }
        return "$path${queryParams.map { "${it.key}=${it.value}" }.joinToString("&")}"
    }

}

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
    val suggestedEvents: Collection<EventReference>
)

/** An enum parameter and the values that may be selected for it. */
public data class EnumPropertyWithOptions(
    val propertyName: String,
    val propertyNullable: Boolean,
    val options: Set<Enum<*>>
)

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

internal fun createParamClassFromCallParameters(parameterClass: KClass<*>, callParams: Parameters): Any {
    val constructors = parameterClass.constructors
    val parameters = mutableMapOf<KParameter, Any?>()

    constructors.first().parameters
        .forEach {
            // A nullable reference (renderReferenceSelect) or enum (renderEnumSelect) is a <select> with its own
            // "(none)" option, not a renderInput() field -- so unlike those, it never gets a null-toggle checkbox.
            // Without this check, every nullable reference/enum select was silently forced to null on submit,
            // regardless of what the user picked, since callParams[nullToggleKey] is always absent for them.
            val isReferenceOrEnumSelect = it.type.isSubtypeOf(ModelID::class.starProjectedType.withNullability(true)) ||
                    it.type.withNullability(false).isSubtypeOf(Enum::class.starProjectedType)
            val nullToggleKey = "null-toggle-${it.name!!}"
            val isNullToggled = callParams[nullToggleKey] != "on"
            if (!isReferenceOrEnumSelect && isNullToggled && it.type.isMarkedNullable) {
                parameters[it] = null
            } else {
                val value = valueWithCorrectType(callParams[it.name!!], it.type)
                if (value != null) {
                    parameters[it] = value
                    //    log.debug { "set ${it.name} to $value}" }
                } else {
                    if (it.type.isMarkedNullable) {
                        parameters[it] = value
                    } else {
                        // throw IllegalArgumentException("${it.name} is null but it is not nullable and it is not optional")
                    }
                }
            }
        }
    return constructors.first().callBy(parameters)
}

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
    val command = Command(
        event = event,
        params = params,
        model = null,
    )
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
                text = Gson().toJson(
                    ValidationResponse(
                        propertyProblems = fieldProblems,
                        propertyCollectionProblems = propertyCollectionProblems,
                        formProblems = emptyList(),
                        dryRunProblems = dryRunProblems
                    )
                )
            )
        }

        is CommandResult.Success -> call.respond(HttpStatusCode.OK)
    }
}

/**
 * The JSON that the bundled form validation script expects. Note that [ValResponse] must have the same shape.
 */
public data class ValidationResponse(
    val propertyProblems: List<ValidationProblemResponse>,
    val propertyCollectionProblems: List<String>,
    val formProblems: List<String>,
    val dryRunProblems: List<String>
)

/**
 * Renders a form built by [FormTemplate.build]. Several forms may be rendered on the same page.
 *
 * @param postPath overrides where the form is submitted, if it was not given when the template was created.
 */
public fun <T : Any, C : KlerkContext, V> FlowContent.eventForm(
    form: EventForm<T, C, V>,
    postPath: String? = null,
): Unit = form.renderInto(this, postPath)

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

    override fun close(): Unit = source.close()
}

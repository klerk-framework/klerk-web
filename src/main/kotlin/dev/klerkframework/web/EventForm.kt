package dev.klerkframework.web
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.camelCaseToPretty
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.NumberContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.web.assets.formJs
import dev.klerkframework.web.assets.uploadJs
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import kotlinx.html.InputType.checkBox
import kotlinx.html.InputType.dateTimeLocal
import kotlinx.html.InputType.email
import kotlinx.html.InputType.number
import kotlinx.html.InputType.password
import kotlinx.html.InputType.text
import mu.KotlinLogging

/** The most rows a multi-line text field is drawn with. */
private const val MULTILINE_ROWS = 8

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
            val property = requireNotNull(template.parameters.field(propertyName)).property
            +(
                template.labelProvider?.invoke(
                    UIElementData(propertyName, dummyContainerFor(propertyName), true),
                ) ?: translator.klerk.property(property)
                )
        }
        val declaration = template.blobDeclarationFor(propertyName)
        input(InputType.file) {
            id = elementId(propertyName)
            // Named so that a submit without JavaScript still carries the file. The script removes the name when it
            // takes over, so the bytes are not sent twice.
            name = propertyName
            attributes["data-klerk-file"] = propertyName
            required = !template.parameters.fields.single { it.name == propertyName }.isNullable
            // Derived from the property's AttachedBlobContainer, the same way maxlength is derived from a
            // StringContainer. It only helps the user pick the right file; what actually keeps a wrong one out is the
            // check the command makes against the bytes.
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
        template.parameters.fields.single { it.name == propertyName }.dummyContainer()

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
            optGroup {
                for (option in prop.options) {
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
            optGroup {
                for (enumValue in prop.options) {
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
        parameters: ObjectSchema<T>,
        params: T?,
        classProvider: CssClassProvider?,
    ): FlowContent.() -> Unit = {
        val prop = parameters.fields.single { it.name == propertyName }
        val isNullable = prop.isNullable
        // A prefilled value can legitimately be null (a nullable param that isn't set); fall back to a default so
        // there is still a container to derive the input type and constraints from.
        val prefilled: DataContainer<*>? = if (params == null) null else getParamDatatype(propertyName, params)
        val hasValue = params == null || prefilled != null
        val value: DataContainer<*> = prefilled
            ?: prop.defaultContainer
            ?: prop.dummyContainer()

        if (isNullable && type != InputType.hidden) {
            apply(renderNullableToggle(propertyName, hasValue))
        }
        when (type) {
            text -> this.apply(
                renderTextInput(
                    propertyName,
                    value,
                    text,
                    classProvider.attr(UiPart.Form, "input", propertyName),
                ),
            )

            email -> this.apply(
                renderTextInput(
                    propertyName,
                    value,
                    email,
                    classProvider.attr(UiPart.Form, "input", propertyName),
                ),
            )

            password -> this.apply(
                renderTextInput(
                    propertyName,
                    value,
                    password,
                    classProvider.attr(UiPart.Form, "input", propertyName),
                ),
            )

            number -> {
                when (value) {
                    is NumberContainer<*> -> this.apply(renderNumberInput(propertyName, value))
                    is DurationContainer -> this.apply(renderDurationInput(propertyName, value))
                    else -> throw IllegalStateException(
                        "Cannot render '$propertyName' as a number: it is a ${value::class.simpleName}",
                    )
                }
            }

            dateTimeLocal -> this.apply(renderInstantInput(propertyName, value as InstantContainer))

            InputType.date -> this.apply(renderDateInput(propertyName, value as DateContainer))

            checkBox -> this.apply(
                renderCheckboxInput(
                    propertyName,
                    value,
                ),
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

    private fun paramValue(propertyName: String, params: T): Any? =
        requireNotNull(template.parameters.field(propertyName)).get(params)

    private fun getModelId(propertyName: String, params: T): ModelID<*>? =
        paramValue(propertyName, params) as? ModelID<*>

    private fun createLabel(
        propertyName: String,
        // typeInstance: DataContainer<*>,
        // enabled: Boolean
    ): FlowContent.() -> Unit = {
        // val elementData = UIElementData(propertyName, typeInstance, enabled)
        val description =
            translator.klerk.propertyDescription(requireNotNull(template.parameters.field(propertyName)).property)
        if (description != null) {
            label(classes = "tooltip") {
                attributes["data-description"] = description
                id = labelId(propertyName)
                attributes["data-label-for"] = propertyName
                htmlFor = elementId(propertyName)
                // if (!enabled) {
                //  style = "opacity: 0.5;"
                // }
                //  +(template.labelProvider?.invoke(elementData) ?: camelCaseToPretty(propertyName))
//            +translator.translateProperty(propertyName)
                val property = requireNotNull(template.parameters.field(propertyName)).property
                +translator.klerk.property(property)
                // +camelCaseToPretty(propertyName)
            }
        } else {
            label {
                id = labelId(propertyName)
                attributes["data-label-for"] = propertyName
                htmlFor = elementId(propertyName)
                // if (!enabled) {
                //  style = "opacity: 0.5;"
                // }
                //  +(template.labelProvider?.invoke(elementData) ?: camelCaseToPretty(propertyName))
//            +translator.translateProperty(propertyName)
                val property = requireNotNull(template.parameters.field(propertyName)).property
                +translator.klerk.property(property)
                // +camelCaseToPretty(propertyName)
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

    private fun renderNullableToggle(propertyName: String, enabled: Boolean): FlowContent.() -> Unit = {
        val checkboxName = "null-toggle-$propertyName"
        input(checkBox) {
            id = elementId(checkboxName)
            name = checkboxName
            value = "on"
            checked = enabled
            autoComplete = "off"
            attributes["onchange"] = "let e = document.getElementById('${elementId(propertyName)}'); " +
                """e.style.display = this.checked ? null : "none"; e.disabled = !this.checked;"""
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

    private fun renderCheckboxInput(propertyName: String, theValue: DataContainer<*>): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        theValue as BooleanContainer
        input(checkBox) {
            id = elementId(propertyName)
            name = propertyName
            value = "true"
            checked = theValue.valueOrNullIfNotAuthorized ?: false
            //  disabled = theValue == null
        }
        input(InputType.hidden) {
            name = propertyName
            value = "false"
        }
        // required = !property.returnType.isMarkedNullable
    }

    private fun renderNumberInput(propertyName: String, theValue: NumberContainer<*>): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(number) {
            // perhaps use `type=text inputmode=numeric` instead?
            id = elementId(propertyName)
            name = propertyName
            value = theValue.valueOrNullIfNotAuthorized?.toString() ?: ""
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
            // required = !property.returnType.isMarkedNullable
            min = theValue.min.toString()
            max = theValue.max.toString()
            if (theValue.hasDecimals) {
                step = "any"
            }
        }
        apply(createErrorPlaceholder(propertyName))
    }

    /**
     * A `datetime-local` input. The browser sends local time without a zone, so the submitted value is interpreted
     * in the server's time zone (see valueWithCorrectType).
     */
    private fun renderInstantInput(propertyName: String, theValue: InstantContainer): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(InputType.dateTimeLocal) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.valueOrNullIfNotAuthorized
                ?.let { theValue.value.toLocalDateTime(TimeZone.currentSystemDefault()).format(dateTimeLocalFormat) }
                ?: ""
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
        }
        apply(createErrorPlaceholder(propertyName))
    }

    /** A `date` input — a calendar date with no time of day or time zone. */
    private fun renderDateInput(propertyName: String, theValue: DateContainer): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(InputType.date) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.valueOrNullIfNotAuthorized?.let { theValue.value.toString() } ?: ""
            attributes["aria-invalid"] = "false"
            attributes["aria-errormessage"] = errorId(propertyName)
        }
        apply(createErrorPlaceholder(propertyName))
    }

    /** A duration, expressed as a number of seconds. */
    private fun renderDurationInput(propertyName: String, theValue: DurationContainer): FlowContent.() -> Unit = {
        apply(createLabel(propertyName))
        input(number) {
            id = elementId(propertyName)
            name = propertyName
            value = theValue.valueOrNullIfNotAuthorized?.let { theValue.value.inWholeSeconds.toString() } ?: ""
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
        if (type == text && theValue.maxLines > 1) {
            textArea(rows = minOf(theValue.maxLines, MULTILINE_ROWS).toString(), classes = classes) {
                id = elementId(propertyName)
                name = propertyName
                attributes["aria-invalid"] = "false"
                attributes["aria-errormessage"] = errorId(propertyName)
                minLength = theValue.minLength.toString()
                required = theValue.minLength > 0
                maxLength = theValue.maxLength.toString()
                +(theValue.valueOrNullIfNotAuthorized ?: "")
            }
        } else {
            input(type, classes = classes) {
                id = elementId(propertyName)
                name = propertyName
                value = theValue.valueOrNullIfNotAuthorized ?: ""
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
        }
        apply(createErrorPlaceholder(propertyName))
    }

    private fun getParamDatatype(propertyName: String, params: T): DataContainer<*>? =
        paramValue(propertyName, params) as DataContainer<*>?

    private fun getModelIdValue(propertyName: String, params: T): Int? = getModelId(propertyName, params)?.value

    private fun getEnumValue(propertyName: String, params: T): String? =
        (paramValue(propertyName, params) as? EnumContainer<*>)?.valueOrNullIfNotAuthorized?.name

    internal fun renderInto(tag: FlowContent, postPath: String? = null) {
        val emptyNonNullableReferenceSelects = referenceSelects.filter { !it.propertyNullable && it.options.isEmpty() }
        if (emptyNonNullableReferenceSelects.isNotEmpty()) {
            tag.p {
                val fields = emptyNonNullableReferenceSelects.joinToString(", ") { it.propertyName }
                +(
                    "${template.defaultValues.eventReference.eventName} is not possible since there are no options " +
                        "available for the required field(s): $fields"
                    )
            }

            for (select in emptyNonNullableReferenceSelects) {
                for (event in select.suggestedEvents) {
                    template.autoButtons?.let { ab ->
                        tag.p {
                            with(ab.support) {
                                eventButton(
                                    event,
                                    null,
                                    context,
                                    onCancelPath = currentUri,
                                    onSuccessAndModelExistPath = currentUri,
                                )
                            }
                        }
                    }
                }
            }
            return
        }
        val path = getPath(postPath, queryParams)
        tag.script {
            src = template.pathProvider.assetPath(formJs.getPathAndHash())
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
                attributes["data-klerk-event"] = template.defaultValues.eventReference.toString()
            }
            +System.lineSeparator()

            // csrf-token should be placed before non-hidden inputs (see
            // https://portswigger.net/web-security/csrf/preventing#how-should-csrf-tokens-be-transmitted)
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
                            template.classProvider,
                        ),
                    )
                }
                +System.lineSeparator()
            }
            for (propertyName in fileInputs) {
                p { tag.apply(renderFileInput(propertyName)) }
                +System.lineSeparator()
            }
            for (refSelect in referenceSelects) {
                p { tag.apply(renderReferenceSelect(refSelect, params)) }
            }
            for (enumSelect in enumSelects) {
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
                                template.classProvider,
                            ),
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
            postPath ?: this.postPath,
        ) { "postPath must be provided, either when building the form or when rendering the form" }
        if (path.contains("?")) {
            path += "&"
        } else {
            path += "?"
        }
        return "$path${queryParams.map { "${it.key}=${it.value}" }.joinToString("&")}"
    }
}

/**
 * Renders a form built by [FormTemplate.build]. Several forms may be rendered on the same page.
 *
 * [postPath] overrides where the form is submitted, if it was not given when the template was created.
 */
public fun <T : Any, C : KlerkContext, V> FlowContent.eventForm(form: EventForm<T, C, V>, postPath: String? = null) {
    form.renderInto(this, postPath)
}

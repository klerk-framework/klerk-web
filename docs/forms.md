# Forms

Most events require some user input. Klerk-web can generate HTML forms for your events and parse the submitted data.

Say we have a parameter class and DataContainers that look like this:

```kotlin
data class CreateAuthorParams(val name: Name, val nobelPrizes: NobelPrizes) : Validatable {
    override fun validators() = setOf(::cannotHaveMoreNobelPrizesThanLettersInTheirName)

    private fun cannotHaveMoreNobelPrizesThanLettersInTheirName(): PropertyCollectionValidity =
        if (nobelPrizes.value > name.value.length) Invalid() else Valid
}

class Name(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines = 1
}

class NobelPrizes(value: Int) : IntContainer(value) {
    override val min = 0
    override val max = 10
}
```

We now want to render a form for CreateAuthorParams.

## The parameter class is the form's only source of truth

A form contains exactly the parameters of its event, nothing else. If a field cannot be expressed as an
`EventParameter`, it does not belong in a `FormTemplate` - render that form yourself. (The CSRF token and the
idempotence key are transport mechanics, not fields, which is why they are allowed.)

This keeps the UI from pushing things into your domain model that do not belong there.

## Generate the form

The form is produced in three steps:

1. A `FormTemplate` is created, typically when the server starts. It validates itself on construction (checking that
every parameter has a matching field declaration), so creating it at startup means you see any errors immediately
instead of on the first request.
If you don't care about the order of the fields, just pass `remaining()` in the init block. In this case we start with the field `nobelPrizes`
and then the remaining fields:
```kotlin
val template = FormTemplate(
    EventWithParameters(CreateAuthor.id, EventParameters(CreateAuthorParams::class)),
    klerk,
    postPath = "/path/to/handle/submission",
    pathProvider = support.pathProvider,
    layout = support.layout,
) {
    text(CreateAuthorParams::nobelPrizes)
    remaining()
}
```

`remaining()` picks an input per property type: text, checkbox, number, a select for references and enums, a
`datetime-local` for `Instant`, a `date` for `Date` and a number of seconds for `Duration`. A property it cannot
render fails here - use `hidden()` or `populatedAfterSubmit()` for it, or build the form yourself.

2. Build an instance of the form when rendering a page.
```kotlin
val form = template.build(
    call,
    params,
    this,
    translator = context.translation,
    context = context,
)
```

3. Render the instance.
```kotlin
call.respondHtml {
    body {
        eventForm(form)
    }
}
```

Several forms may be rendered on the same page.

## Files

A parameter that is an `AttachedBlobContainer` is declared with `file()`, and needs the [Uploads](uploads.md) plugin:

```kotlin
val template = FormTemplate(
    EventWithParameters(CreateDocument.id, EventParameters(CreateDocumentParams::class)),
    klerk,
    postPath = "/documents",
    pathProvider = support.pathProvider,
    uploads = uploadPlugin,
) {
    file(CreateDocumentParams::content)
    remaining()
}
```

The bytes never travel with the form. The browser uploads them while the user fills in the rest, and the form carries
only the id of the upload; `parse` turns that into attached data, so the command that stores it is as quick as any
other. Without JavaScript the file posts with the form and the server does the upload in one request.

The upload id is transport, like the CSRF token and the idempotence key — the parameter class is still the form's only
source of truth. `remaining()` never renders a file field by itself: uploading needs the plugin, so it has to be
declared.

The validation described below never touches the file: a dry run stands the file field in with a placeholder blob so
that every other field is still checked, and neither uploads the bytes again nor consumes the upload. That means a
form with a file field should answer `ParseResult.DryRun` with `200 OK`, as in the example below, rather than with
`respondDryRun` — issuing the command would report the placeholder as attached data that does not exist.

What the property's container declares is rendered the same way `maxLength` is rendered for a string: `accept` becomes
the file input's `accept` attribute, and `maxSize` stops a too-large file before it is uploaded and is applied by the
upload endpoint as well. As always the browser's version of a rule is a convenience — the one that decides is the
check Klerk makes against the bytes when the command attaches them.

## Handle the submission

When the form is submitted, the server will receive a POST request to the specified path.
Here is an example of how to use the same FormTemplate to parse the request:
```kotlin
routing {
    post("/path/to/handle/submission") {
        val context = call.ctx(klerk)
        when (val parsed = template.parse(call, context)) {
            is ParseResult.Forbidden -> FormTemplate.respondForbidden(call)
            is ParseResult.Invalid -> FormTemplate.respondInvalid(parsed, call)
            is ParseResult.DryRun -> call.respond(HttpStatusCode.OK)
            is ParseResult.Parsed -> {
                println("Hello ${parsed.params.name}")
            }
        }
    }
}
```

`parse` needs the caller's context so that the messages the user reads are translated for them.

## Validation

Klerk will make sure to evaluate all relevant rules when you pass the parameters in a Klerk command, so there is no
need to worry about malformed data. Klerk-web never validates anything of its own: everything the browser enforces
(`required`, `min`, `max`, `maxlength`, `pattern`) is derived from your `DataContainer`, so there is only ever one
definition of what is valid.

To give a better user experience, problems are shown before the form is submitted. Some rules (e.g. the maximum
number of nobel prizes) are checked by the browser. The rest are evaluated by the server: a little JavaScript sends
"dry-run" requests as the user types. This means validation is not limited to a field or a model - *all* rules are
evaluated, so if you have a rule saying that the name must be unique, the user is told before submitting.

Validation happens in levels, and stops at the first level that finds a problem - all problems within a level are
reported at once, so every offending field is marked:

1. Each property on its own (`DataContainer.validate`).
2. The properties together (`Validatable.validators()`).
3. The command pipeline: authorization and business rules.

Whether the actor may execute the event at all is decided before the form is rendered - use `getPossibleEvents` and
`getPossibleVoidEvents` so nobody fills in a form for an event they can never execute.

Without JavaScript the form still posts and the server validates.

## Tooltips
If you provide a KlerkTranslation with a function propertyDescription that produces a text, the label will get the "tooltip" class and
the data-tooltip attribute will be set to the result of the function. You can use this to provide more information about the field.

## CSRF

Every POST that klerk-web registers is protected with the Double Submit Pattern: a token in a `__Host-` cookie,
repeated in a hidden field. The token is per session, so several forms and several tabs work at the same time. A
request that fails the check gets a 403 - `parse` returns `ParseResult.Forbidden`.

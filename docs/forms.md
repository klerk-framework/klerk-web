# Forms

Most events require some user input. Klerk-web can generate HTML forms for your events and parse the submitted data.

Say we have a parameter class and DataContainers that look like this:

```kotlin
data class CreateAuthorParams(val name: Name, val nobelPrizes: NobelPrizes) : Validatable {
    override fun validators() = setOf(::cannotHaveMoreNobelPrizesThanLettersInTheirName)

    private fun cannotHaveMoreNobelPrizesThanLettersInTheirName(): PropertyCollectionValidity =
        if (nobelPrizes.value > name.value.length) PropertyCollectionValidity.Invalid()
        else PropertyCollectionValidityValid
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

## Generate the form

The form is produced in three steps:
1. A template is created when the server starts. It is recommended to validate the template at startup so you will immediately see any errors.
If you don't care about the order of the fields, just pass `remaining()` in the init block. In this case we start with the field `nobelPrizes` 
and then the remaining fields:
```kotlin
val template = EventFormTemplate(
    EventWithParameters(CreateAuthor.id, EventParameters(CreateAuthorParams::class)),
    klerk, 
    postPath = "/path/to/handle/submission",
) {
    text(CreateAuthorParams::nobelPrizes)
    remaining()
}
template.validate()
```

2. Build an instance of the form when rendering a page.
```kotlin
val instance = template.build(
    call,
    params,
    this,
    translator = context.translation,
)
```

3. Render the instance.
```kotlin
call.respondHtml {
    body {
        form1.render(this)
    }
}
```

## Handle the submission

When the form is submitted, the server will receive a POST request to the specified path. 
Here is an example of how to use Klerk-web to parse the request:
```kotlin
routing {
    post("/path/to/handle/submission") {
        when (val parsed = template.parse(call)) {
            is Invalid -> EventFormTemplate.respondInvalid(parsed, call)
            is DryRun -> call.respond(HttpStatusCode.OK)
            is Parsed -> {
                println("Hello ${parsed.params.name}")
            }
        }
    }
}
```

## Validation

Klerk will make sure to evaluate all relevant rules when you pass the parameters in a Klerk command, so there is no need
to worry about malformed data. However, to provide a better user experience, Klerk-web will notify the user of any 
even before the form is submitted. Some rules (e.g. the maximum number of nobel prizes) will be validated by the browser.
Other rules (e.g. `cannotHaveMoreNobelPrizesThanLettersInTheirName`) will be evaluated by the server. This is achieved by
a little JavaScript that sends "dry-run" requests to the server. This means that the validation is not limited to the 
fields or model, but all rules are evaluated. So if you introduce a rule saying that the name must be unique, the user
will be notified if it is already taken before submitting the form.

# Klerk-web

Klerk-web is a set of tools that helps you build a server-side rendered (SSR) web application with 
[Klerk](https://klerkframework.dev/). It should be used together with [Ktor](https://ktor.io). 

# Installation

Typically, you will generate a Ktor project using [the Ktor project generator](https://start.ktor.io/) or from within 
IntelliJ (File→New→Project). You then add Klerk and Klerk-web to your project:  

```kotlin
implementation("com.github.klerk-framework:klerk:$klerk_version")
implementation("com.github.klerk-framework:klerk-web:$klerk_web_version") 
```

For details on how to set up Klerk, see the [Klerk documentation](https://klerkframework.dev/docs/intro).

You will need a LowCodeConfig when using Klerk-web:
```kotlin
val lowCodeConfig = LowCodeConfig(
    basePath = "/admin",
    contextProvider = ::contextFromCall,
    showOptionalParameters = ::showOptionalParameters,
    cssPath = "https://unpkg.com/almond.css@latest/dist/almond.min.css",
    knownAlgorithms = setOf()
)

suspend fun contextFromCall(call: ApplicationCall): Ctx {
    // as described in https://klerkframework.dev/docs/building-config/context a Context is always required when 
    // interacting with Klerk. This function tells Klerk-web how to get this Context.  
}

fun showOptionalParameters(event: EventReference) = false   // this function will not required in the future
```

# Admin UI

To auto-generate an admin-UI for your application, add this in your Ktor routing:
```kotlin
    routing {
        apply(LowCodeMain(klerk, lowCodeConfig).registerRoutes())
    }
```

You can now browse to ´/admin´.

# Forms

Klerk-web helps you render HTML forms for your event parameters. The form will apply the validation rules you have
specified in your Klerk configuration:
* Simple validation rules will be encoded in the HTML form itself. E.g. if you have specified that the FirstName
must have at least 3 letters, the Klerk-web will add `minlength=3` to the `input`.
* Complex validation rules (e.g. validation functions) will have to be evaluated on the server.
Klerk-web handles this by executing a dry-run command in the background. If it fails, the error is rendered into the form. 

There are two ways you can render the forms:

## Easy mode
Klerk-web offers a quick way to implement interactions with your Klerk configuration. It will probably not be good 
enough for the final version of your application, but it can significantly speed up your iteration speed in the beginning
of the project. Klerk-web will render a button for each of your events. When the button is clicked, a form is rendered. 
When the form is submitted, the command is executed.

This is how it can be used when rendering buttons for the void events of a game:
```kotlin
val buttonTargets = ButtonTargets(back = "/", model = "/game/{id}", error = "/")    // where to navigate after form 
call.respondHtml {
    body {
        h2 { +"Things you can do" }
        getPossibleVoidEvents(Game::class).forEach {
            apply(LowCodeCreateEvent.renderButton(it, klerk, null, lowCodeConfig, buttonTargets, context))
        }
    }
```

Similarly, if you have an instance of the game:

```kotlin
call.respondHtml {
    body {
        h3 { +"Things you can do with this game" }
        getPossibleEvents(gameId).forEach {
            apply(LowCodeCreateEvent.renderButton(it, klerk, gameId, lowCodeConfig, buttonTargets, context))
        }
    }
}
```

## Full control mode
Sooner or later you will likely want to have full control of how the form is rendered. When rendering a form this way, 
there are four steps:

### Create a template
We will first create a template for the form. This template should be created once when the application starts. It is
recommended to call `validate()` on the template on application startup so that we can fail fast if there is a problem
with the template. Assuming you have a parameter class `CreateAuthorParams`:
```kotlin
val createAuthorFormTemplate = EventFormTemplate(
    EventWithParameters(
        CreateAuthor.id,
        EventParameters(CreateAuthorParams::class)
    ), klerk, "/",
) {
    text(CreateAuthorParams::firstName)
    text(CreateAuthorParams::lastName)
    text(CreateAuthorParams::phone)
    number(CreateAuthorParams::age)
    populatedAfterSubmit(CreateAuthorParams::secretToken)
}
```

### Build the form
Before calling `call.respondHtml` we need to build the form:
```kotlin
val initialValues = CreateAuthorParams(
    firstName = FirstName("James"),
    lastName = LastName("Clavell"),
)
val createAuthorForm = klerk.read(context) {
    createAuthorFormTemplate.build(call, initialValues, this, translator = context.translator)
}
```

### Render the form
Now we can render the form like this:
```kotlin
call.respondHtml {
    body {
        createAuthorForm.render(this)
    }
}
```

### Parse the data
When the form is submitted, we need to parse the data into an instance of the parameter class. The template will help
with this:
```kotlin
when (val result = createAuthorFormTemplate.parse(call)) {
    is Invalid -> EventFormTemplate.respondInvalid(result, call)
    is DryRun -> // TODO: describe what to do here
    is Parsed -> // the parameters are now available in result.params
}
```

# Render a list of models
TODO

# Render a model
TODO

# Examples
To learn more how Klerk-web can be used, see the [examples](https://github.com/search?q=org%3Aklerk-framework+example&type=repositories). 

# Auto Buttons

It is possible to automatically render a button for each possible event. When the user clicks the button, a form is 
rendered. And when the user submits the form, the event is triggered. 

First, create an instance of `AutoButtons`:

```kotlin
val autoButtons = AutoButtons(klerk, "autobuttons-path", ApplicationCall::ctx, cssPath)
```

Register the routes so that AutoButtons can render a form and handle the submission:

```kotlin
routing {
    apply(autoButtons.registerRoutes())
}
```

Now you can render buttons for each possible event. For void events:

```kotlin
val context = call.ctx(klerk)
klerk.readSuspend(context) {
    call.respondHtml {
        body {
            getPossibleVoidEvents(Author::class).forEach {
                apply(autoButtons.render(it, null, context))
            }
        }
    }
}
```

And for instance events:

```kotlin
val context = call.ctx(klerk)
klerk.readSuspend(context) {
    val author = get(id)
    call.respondHtml {
        body {
            getPossibleEvents(id).forEach { event ->
                apply(autoButtons.render(event, id, context))
            }
        }
    }
}
```

## Configuration

When creating an instance of `AutoButtons`, you can specify the path to the CSS that should be used when rendering the
form. You can also specity a CssClassProvider with functions that will be called to get the CSS classes when rendering
the form.

To control where the browser should be redirected after submitting the form, you can specify
* onCancelPath: 
* onSuccessAndModelExistPath: 
* onErrorPath: 

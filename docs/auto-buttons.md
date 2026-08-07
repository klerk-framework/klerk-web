# Auto Buttons

It is possible to automatically render a button for each possible event. When the user clicks the button, a form is 
rendered. And when the user submits the form, the event is triggered. 

First, create an instance of `AutoButtons`:

```kotlin
val pathProvider = DefaultPathProvider()
val autoButtons = AutoButtons(klerk, ApplicationCall::ctx, pathProvider)
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
call.respond(klerk.read(context) {
    html {
        body {
            getPossibleVoidEvents(Author::class).forEach {
                apply(autoButtons.render(it, null, context))
            }
        }
    }
})
```

And for instance events:

```kotlin
val context = call.ctx(klerk)
call.respond(klerk.read(context) {
    html {
        body {
            getPossibleEvents(id).forEach { event ->
                apply(autoButtons.render(event, id, context))
            }
        }
    }
})
```

## Configuration

When creating an instance of `AutoButtons`, you can optionally specify a `CssClassProvider` with functions that will
be called to get the CSS classes when rendering the form.

`render` takes optional path parameters that control where the browser is redirected once the form has been handled:
* `onCancelPath`: where to redirect if the user cancels the form.
* `onSuccessAndModelExistPath`: where to redirect after a successful event, if the model still exists.
* `onErrorPath`: where to redirect if the event fails.

All three default to `/` if not specified.

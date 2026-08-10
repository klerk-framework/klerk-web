# Auto Buttons

Renders a button for an event. Clicking it leads to a page with a form; submitting the form issues the command.

`AutoButtons` comes with the `WebSupport`, so there is usually nothing to create:

```kotlin
val autoButtons = support.autoButtons
```

Register the routes so that it can render the form and handle the submission:

```kotlin
routing {
    autoButtonsRoutes(support.autoButtons)
}
```

Now you can render a button for each possible event. `eventButton` needs the [WebSupport](introduction.md#websupport)
in scope, which `respondPage` provides. For void events:

```kotlin
val context = call.ctx(klerk)
val events = klerk.read(context) { getPossibleVoidEvents(Author::class) }
support.respondPage(call, "New author") {
    events.forEach { eventButton(it, null, context) }
}
```

And for instance events:

```kotlin
val context = call.ctx(klerk)
val events = klerk.read(context) { getPossibleEvents(id) }
support.respondPage(call, "Author") {
    events.forEach { event -> eventButton(event, id, context) }
}
```

Rendering a fragment rather than a whole page? Bring the support into scope with `with`:

```kotlin
call.respond(klerk.read(context) {
    html {
        body {
            with(support) {
                getPossibleEvents(id).forEach { event -> eventButton(event, id, context) }
            }
        }
    }
})
```

An event is never triggered by the button itself, not even one without parameters: the button is always a link to a
page that renders a form. That form is what carries the CSRF token.

## Where to go afterwards

`render` takes optional paths that control where the browser goes once the form has been handled:

* `onCancelPath`: if the user cancels the form.
* `onSuccessAndModelExistPath`: after a successful event, if the model still exists.
* `onErrorPath`: if the event fails.

All three default to `/`.

## Events klerk-web cannot render

klerk-web builds a form for every event when the application starts. Some parameter shapes are not supported - a
collection of references (`Set<ModelID<Author>>`), a nested value object, or attached data. Such an event is
reported at ERROR when the application starts and then skipped: no button is rendered for it, and its form page
answers 404.

Render those events yourself with your own form.

Use `eventFilter` to exclude further events that you want to handle yourself:

```kotlin
val support = WebSupport(klerk, ApplicationCall::ctx, eventFilter = { it != DeleteEverything.id })
```

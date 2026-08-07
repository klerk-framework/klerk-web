# Introduction

Klerk-web is a collection of tools that help you build SSR (server-side rendered) web applications using [Klerk](https://klerkframework.dev/) and [Ktor](https://ktor.io). 
You don't have to use all tools, e.g. if you want to use client-side rendering for the users but an SSR admin-UI, you can use Klerk-web for
only that part.


## Installation

If you want to build a web application using Klerk, you will typically generate a Ktor project
using [the Ktor project generator](https://start.ktor.io/) or from within IntelliJ (File→New→Project).
You then add Klerk and Klerk-web to your project:

```kotlin
implementation(platform("dev.klerkframework:klerk-bom:$klerkBomVersion"))
implementation("dev.klerkframework:klerk")
implementation("dev.klerkframework:klerk-web")
```

# Context
Interactions with Klerk almost always require a context. We therefore need a way to create a context
from a Ktor call. The recommended way is to create an extension function that returns a context:
```kotlin
suspend fun ApplicationCall.ctx(klerk: Klerk<Ctx, Views>): Ctx {
    // your code here
}
```

# Building with Klerk

Assuming you already have a Klerk configuration, the tools and building blocks in klerk-web can be used to quickly
turn the configuration into a fully functional web application.

While it is possible to tweak and configure the building blocks, it is likely that you
at some point will replace some or all parts with custom code to better fit your needs.

It is recommended to use [HTML DSL](https://ktor.io/docs/server-html-dsl.html) to produce the HTML. Klerk-web comes with an extension function `Reader.html`
that makes it easy to do so:
```kotlin
val context = call.ctx(klerk)
call.respond(klerk.read(context) {
    html {
        body {
            h1 { +get(id).props.name }
        }
    }
})
```

## Building blocks
Klerk-web provides a set of building blocks that can be used to build a UI for Klerk:
* KlerkWeb: A facade that wires up AutoButtons and AdminUI, and generates list and detail routes for every managed model. The fastest way to get a working UI (see [Quick start](#quick-start) below).
* TableTemplate: Build and render a paginated, filterable table for a collection.
* FormTemplate: Generate forms and parse submitted data (see [Forms](forms.md)).
* AutoButtons: Generate a button for an event. When the button is clicked, a form is rendered. When the form is
submitted, the data is parsed and a Klerk command is issued (see [AutoButtons](auto-buttons.md)).
* AdminUI: Manage your application (see [Admin UI](admin-ui.md)).

## Quick start

If you want routes for every managed model (a list page and a detail page each) without assembling the building
blocks yourself, use `KlerkWeb`:

```kotlin
val klerkWeb = KlerkWeb(klerk, ApplicationCall::ctx)

routing {
    apply(klerkWeb.generateRoutes())
}
```

This registers a list and a detail route for each managed model, plus the AutoButtons and Admin UI routes.
`klerkWeb.generateNav()` renders a `<nav>` with a link to each model's list page.

## Ask Klerk

One feature of Klerk is that it is easy to ask for the configuration of the application. This can be used to
keep the UI in sync with the configuration. These methods are available when you read:
* getPossibleVoidEvents: Given a model class, it will tell you which event(s) can be used to create a new instance.
* getPossibleEvents: Give it a ModelID and Klerk will figure out all events that can be applied to it considering the current state.
  You can use these methods e.g. to figure out if a certain button should be visible or not.

An even more powerful approach is to combine these methods with other building blocks to create a UI that automatically
follows the configuration. So if the Klerk configuration changes, your UI will automatically update. Example:

```kotlin
val context = call.ctx(klerk)
 {
    val model = get(id)
    call.respond(klerk.read(context) {
        body {
            h1 { +"Actions for ${model.props.name}" }
            getPossibleEvents(model.id).forEach { event ->
                apply(autoButtons.render(event, model.id, context))
            }
        }
    })
}
```

## Appearance

It is recommended to start with a classless CSS. When you are ready to add some styling, you can provide your own
class provider function to customize the CSS classes applied to form elements.

## How to build a basic web UI
Putting it all together, here is a rough guide how to build a basic UI:
* Start with a classless CSS. Use the [assets](assets.md) tools provided by klerk-web to serve it.
* Call `KlerkWeb(klerk, contextProvider).generateRoutes()` (see [Quick start](#quick-start)) to get a list page and a
  detail page for every managed model, with buttons for every possible event already wired up.
* If you need a custom list or detail page for a specific model, build it with `TableTemplate`/`FormTemplate`/`AutoButtons`
  directly instead of relying on the generated route for that model. Or build it yourself from scratch without klerk-web.

# Introduction

Klerk-web is a set of building blocks for SSR (server-side rendered) web applications built with
[Klerk](https://klerkframework.dev/) and [Ktor](https://ktor.io).

Pick the blocks you want. When one no longer fits, replace that block with your own code; the rest keep working. If
you want an SPA, you can still use klerk-web for the admin UI if you want.

The blocks produce semantic HTML, with progressive enhancement, stylable with CSS classes.

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

# WebSupport

Every building block takes a `WebSupport`. It carries what they all need: Klerk, the context provider, where pages
live, what they look like and how they are styled.

```kotlin
val support = WebSupport(
    klerk,
    ApplicationCall::ctx,
    pathProvider = DefaultPathProvider(),
    layout = Layout(css = css),
    classProvider = null,
)
```

Pass the same instance to every block, so the pages link to each other and look alike.

* `PathProvider` builds the URLs klerk-web links to. Override `pathForItem` to return null for a model that has no
  detail page - nothing will link to it, and no route is registered for it.
* `Layout` produces whole documents: `lang`, `<title>`, viewport, the stylesheet and anything else you want in
  `<head>`. See [Appearance](appearance.md).
* `CssClassProvider` supplies CSS classes. Leave it out when using a classless CSS.

# Rendering a page

The blocks are ordinary [HTML DSL](https://ktor.io/docs/server-html-dsl.html) functions, so they are called like any
other tag. `respondPage` renders a whole document and makes the support available to the blocks inside it:

```kotlin
support.respondPage(call, "Authors") {
    h1 { +"Authors" }
    modelTable(table)
    events.forEach { event -> eventButton(event, id, context) }
}
```

Rendering a fragment instead - an HTMX partial, or your own `respondHtml` - works the same way once the support is in
scope:

```kotlin
call.respond(klerk.read(context) {
    html {
        body {
            with(support) { eventButton(event, id, context) }
        }
    }
})
```

Blocks that carry everything they need - `modelTable`, `eventForm`, `modelsNav`, `modelProperties` - require no scope
at all.

# Building blocks

* [ModelListPage and ModelDetailPage](model-pages.md): a generated list and detail page for one model.
* [TableTemplate](tables.md): a paginated, filterable table of a collection. Columns are values you can replace.
* [FormTemplate](forms.md): generate a form for an event and parse what is submitted.
* [AutoButtons](auto-buttons.md): a button for an event; clicking it renders the form and issues the command.
* [Admin UI](admin-ui.md): an operations console. An **internal tool** - not a foundation for your users' UI.
* [Assets](assets.md): serve CSS and JavaScript with cache busting and compression.
* [Plugins](plugins.md): give a Klerk plugin its own page in the Admin UI.

It is recommended to use [HTML DSL](https://ktor.io/docs/server-html-dsl.html) to produce the HTML. Klerk-web comes
with an extension function `Reader.html` that makes it easy to do so:
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

## Quick start

If you want routes for every managed model without assembling the blocks yourself, use `KlerkWeb`:

```kotlin
val klerkWeb = KlerkWeb(klerk, ApplicationCall::ctx, canSeeAdminUI = ::canSeeAdminUI)

routing {
    klerkWebRoutes(klerkWeb)
}
```

This registers a list and a detail route for each managed model, plus the AutoButtons and Admin UI routes.
`modelsNav(klerkWeb)` renders a `<nav>` with a link to each model's list page.

`canSeeAdminUI` has no default: the Admin UI exposes the log, the configuration and job control, so you must decide
who may see it.

To build some pages yourself, exclude those models:

```kotlin
klerkWebRoutes(klerkWeb, filter = { it.kClass != Game::class })
```

`klerkWeb.support` is the `WebSupport` the generated pages use. Pass it to your own blocks so they match.

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
val (model, events) = klerk.read(context) { Pair(get(id), getPossibleEvents(id)) }
support.respondPage(call, "Actions") {
    h1 { +"Actions for ${model.props.name}" }
    events.forEach { event -> eventButton(event, id, context) }
}
```

## How to build a basic web UI

* Start with a classless CSS. Use the [assets](assets.md) tools to serve it, and give it to the `Layout`.
* Create a `KlerkWeb(klerk, ::ctx, canSeeAdminUI)`, call `klerkWebRoutes(klerkWeb)` and `modelsNav(klerkWeb)` to get a list page and a detail page for every
  managed model, with buttons for every possible event already wired up.
* When you need something else for a specific model, exclude it from `klerkWebRoutes` and build that page with
  `TableTemplate`/`FormTemplate`/`AutoButtons` - or from scratch. The other models keep their generated pages.

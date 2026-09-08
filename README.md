# Klerk-web

Klerk-web is a set of building blocks for server-side rendered (SSR) web applications built with
[Klerk](https://klerkframework.dev/) and [Ktor](https://ktor.io). Pick the blocks you want; replace one with your
own code when it no longer fits.

## Installation

If you want to build a web application using Klerk, you will typically generate a Ktor project
using [the Ktor project generator](https://start.ktor.io/) or from within IntelliJ (File→New→Project).
You then add Klerk and Klerk-web to your project:

```kotlin
implementation(platform("dev.klerkframework:klerk-bom:$klerkBomVersion"))
implementation("dev.klerkframework:klerk")
implementation("dev.klerkframework:klerk-web")
```

## Documentation

Start with the [introduction](docs/introduction.md).

* [Model pages](docs/model-pages.md) - a generated list and detail page for a model
* [Tables](docs/tables.md) - a paginated, filterable table; columns are values
* [Forms](docs/forms.md) - generate a form for an event and parse what is submitted
* [Auto buttons](docs/auto-buttons.md) - a button that renders the form and issues the command
* [Uploads](docs/uploads.md) - resumable file upload, ending in attached data
* [Serving attached data](docs/serving-attached-data.md) - the route that serves blobs and strings back out
* [Images](docs/images.md) - serving an image in the size the page needs
* [Admin UI](docs/admin-ui.md) - an operations console; an internal tool
* [Appearance](docs/appearance.md) - layout, semantic HTML and CSS classes
* [Assets](docs/assets.md) - serving CSS and JavaScript
* [Plugins](docs/plugins.md) - giving a Klerk plugin its own admin page

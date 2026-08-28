# Model pages

A generated list page and detail page for one model. These are the pages `klerkWebRoutes(klerkWeb)` produces; you
can also use them one at a time.

```kotlin
val authors = ModelListPage<Author, Ctx, Views>(
    Author::class, support, pathToList = "/author", humanName = "Authors",
)
val author = ModelDetailPage<Author, Ctx, Views>(Author::class, support, humanName = "Author")

routing {
    modelListRoutes(authors)
    modelDetailRoutes(author)
}
```

Or call them from a route of your own, when you want to decide the path yourself:

```kotlin
get("/writers") { authors.respond(call) }
```

## The list page

A [table](tables.md) of every model in the collection, plus a button for each event that can create one.

## The detail page

The model's properties (a reference is a link to that model's own page), its metadata, a button for every event
that is possible in the model's current state, and the models that refer to it.

A `DateContainer` renders as `2026-08-22`, an `InstantContainer` as `2026-08-22 14:30:00` and a
`DurationContainer` as `1h 30m`. Instants are shown in the server's time zone.

```kotlin
ModelDetailPage(
    Author::class,
    support,
    humanName = "Author",
    eventLogPath = "/admin/_eventlog",   // adds a "History" button
    useTable = true,               // <table> instead of <dl>
    extraContent = { kClass, model -> { p { +"Anything you like" } } },
)
```

## Models without a detail page

`PathProvider.pathForItem` returns null for a model that has no detail page. Then no route is registered for it, and
nothing links to it - lists render plain text instead of links, and so do references from other models' pages.

```kotlin
class MyPaths : PathProvider by DefaultPathProvider() {
    override fun pathForItem(kClass: KClass<out Any>, id: ModelID<*>): String? =
        if (kClass == Secret::class) null else "/${kClass.simpleName?.lowercase()}/${id.value}"
}
```

Use this together with `klerkWebRoutes(klerkWeb, filter = ...)` when you build some pages yourself.

# Tables

`TableTemplate` renders a paginated, filterable table of the models in a collection.

```kotlin
val template = TableTemplate(klerk, Author::class, support)

get("/authors") {
    val context = call.ctx(klerk)
    val table = klerk.read(context) {
        template.build(klerk.config.views.authors.all, this, call)
    }
    call.respond(klerk.read(context) {
        html {
            body { modelTable(table) }
        }
    })
}
```

`build` reads the collection, applying the paging and filtering in the request's query parameters. `render` produces
the filter controls, the table and the pagination links.

## Columns

Columns are values, not settings. `Column.defaults()` gives the standard four - Created, Updated, State and a summary
of the properties - and you map over that list to change them:

```kotlin
val columns = listOf(
    Column<Author>("Name") { model -> +model.props.name.value },
) + Column.defaults<Author>().filter { it.header == "State" }

TableTemplate(klerk, Author::class, support, columns)
```

A `Column` is a header and a function that renders one `<td>`:

```kotlin
Column<Game>("Opponent") { model -> a(href = "/player/${model.props.opponent.value}") { +"View" } }
```

Rows link to the model's detail page. If `PathProvider.pathForItem` returns null for that model, the cells are
rendered as plain text instead, so no table ever contains a dead link.

## Filtering

The rendered filter controls set query parameters, which `build` picks up:

* `collection` - which of the model's collections to show.
* `filterState` - only models in one state.
* `filterString` - a small filter language, e.g. `created>2023-03-10T20:23:13Z`.
* `cursor` - the page to show.

Anything beyond that is a good reason to build the page yourself: read the collection with your own query and render
it however you like.

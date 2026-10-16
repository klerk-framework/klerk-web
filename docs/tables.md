# Tables

`TableTemplate` renders a paginated table of the models in a collection. The reader can sort it by clicking a column
header and filter it on its columns.

```kotlin
val template = TableTemplate(klerk, Author::class, support)

get("/authors") {
    val context = call.ctx(klerk)
    val table = klerk.read(context) {
        template.build(klerk.specification.views.authors.all, this, call)
    }
    support.respondPage(call, "Authors") { modelTable(table) }
}
```

`build` reads the collection, sorting, filtering and paging it as the request's query parameters say. `modelTable`
renders the filter controls, the table and the pagination links.

The reader can switch to any registered view of the model, which sets the `view` parameter, e.g.
`/authors?view=v.Author.greatAuthors`. The collection passed to `build` is shown when the request picks no view.

## Columns

Columns are values, not settings. There are three kinds:

```kotlin
val columns = listOf(
    Column.property(Author::lastName),                      // a property of the model
    Column.value("Books") { model -> bookCount(model) },    // a value computed from the model
    Column("Actions") { model -> a("/edit/${model.id}") { +"Edit" } },   // just a cell
) + Column.state()

TableTemplate(klerk, Author::class, support, columns)
```

* `Column.property` shows one property. The header is the property name made readable.
* `Column.value` shows what a function returns. Its return type decides what can be done with it.
* `Column(header) { cell }` renders whatever you like in the `<td>`, but cannot be sorted or filtered.

`Column.id()`, `Column.state()`, `Column.created()` and `Column.updated()` show the model's metadata.
`Column.defaults()` is those four plus a summary of the properties.

Pass `sortable = false` or `filterable = false` to `property` or `value` to turn either off for that column.

In a `Column(header) { cell }`, render property values with `toString()`, not `.value`: `toString()` formats temporal
types and shows the masked placeholder for unauthorized reads, whereas `.value` throws `AuthorizationException` if the
actor may not read it.

The cells of `property`, `value` and the metadata columns link to the model's detail page. If
`PathProvider.pathForItem` returns null for the model, they are plain text instead. Clicking anywhere on a row also
goes to the detail page.

Rows the current actor is not authorized to read are left out of the page rather than raising an error. The check
happens before the page is cut, so a page is still full whenever there are enough rows the actor may read.

Pass `pageSize` to change how many rows a page holds.

## Sorting

Clicking the header of a sortable column sorts by it ascending, then descending, then back to unsorted. Empty values
come last in both directions.

`defaultSort` is the order when the request does not pick one. Without it, the view's own order is used:

```kotlin
TableTemplate(klerk, Author::class, support, columns, defaultSort = TableSort("lastName", ascending = false))
```

`TableSort` names a column by its `key`: the property name for `property`, the header in lower case with dashes for
`value` (e.g. `"books"`), and `id`, `state`, `created` and `updated` for the metadata. Pass `key` to choose your own.

## Filtering

The Filters panel has a section for each filterable column. A row is shown if it matches any of the conditions given
for a column, in every filtered column.

| Type                                   | Filters                                     |
|----------------------------------------|---------------------------------------------|
| String                                 | any of, contains (ignoring case)            |
| Numbers                                | any of, from-to                             |
| `LocalDate`                            | any of, from-to                             |
| `Instant`                              | from-to                                     |
| `Duration`                             | from-to, in seconds                         |
| Boolean, enum, state                   | any of, picked from the possible values     |
| `ModelID`                              | any of                                      |
| Collections, nested objects, positions | none                                        |

A nullable column can also be filtered on being empty. The active filters are listed above the table, each with a link
that removes it.

`Column.value` supports the same types, and their `DataContainer`s. Any other `Comparable` can only be sorted.

## Controls in the headers

This is experimental. Pass `controls = TableControls.Header` to put the controls by each header instead: ⇡ sorts
ascending (↑ when active), ⇣ descending (↓ when active), and the funnel opens a dialog with the column's filter.
Clicking the active arrow removes the sort. The active controls are shown in full, the others dimmed. The Filters panel
is not shown, but the active filters are still listed above the table.

The dialog is an HTML popover, which needs no JavaScript. The views are a row of links instead of a select, with the
current one marked.

The controls are in a `span` marked `data-table-controls`, and the active ones are marked `data-active`. To show the
controls only while the pointer is over the header, keeping the active ones visible:

```css
@media (hover: hover) {
  th [data-table-controls] { visibility: hidden; }
  th:hover [data-table-controls],
  th:has(:focus-visible) [data-table-controls],
  th [data-table-controls] [data-active] { visibility: visible; }
}
```

## Authorization

Sorting and filtering only see what the actor may read. A value the actor may not read matches no filter, not even
"empty", and is sorted last in both directions. Sorting and filtering on it therefore reveal nothing about it.

## Query parameters

| Parameter                          | Example                    |
|------------------------------------|----------------------------|
| `sort`, `dir`                      | `sort=lastName&dir=desc`   |
| `f.<key>`: any of, comma-separated | `f.score=null,53`          |
| `f.<key>.min`, `f.<key>.max`       | `f.score.min=10`           |
| `f.<key>.contains`                 | `f.lastName.contains=an`   |
| `cursor`: the page to show         | from the pagination links  |

`null` stands for an empty value in a nullable column. Pass `paramPrefix` to put a prefix in front of every name, so
that two tables can share a page. Other query parameters are left alone and kept when the reader sorts, filters or
pages.

An invalid parameter, such as an unknown column or `f.score=abc`, makes `build` set the response status to 400. The
table then shows the problem and a link to the same page without the invalid parameters. `respondPage` and
`Reader.html` keep that status unless you pass one; if you respond in another way, pass on `Table.status`.

## Cost

A sorted or filtered table reads every model in the view on every request. So does the page count in the pagination,
which every table shows.

## Keyboard

The rows can be navigated with the keyboard:

| Key                | Action                         |
|--------------------|--------------------------------|
| `j`, `↓`           | Next row                       |
| `k`, `↑`           | Previous row                   |
| `Home`, `End`      | First and last row             |
| `Enter`            | Open the row's detail page     |

The arrow keys only move between rows when a row or nothing is focused. With several tables on a page, the keys act on
the one holding the focus, or else the first one. Tab moves into the table once and then on past it.

The keys are ignored while the user types in a field or a dialog or popover is open. A page that uses one of the keys
itself can claim it by calling `preventDefault()` in a `keydown` listener on `document`.

Only rows whose model has a detail page (see `PathProvider.pathForItem`) can be reached. The focused row's link is
marked `data-klerk-row-link`.

## Highlighting the row under the pointer

klerk-web does not add CSS of its own. Add the hover and focus styles to your stylesheet:

```css
tbody tr:hover {
    background: rgba(0, 0, 0, 0.05);
    cursor: pointer;
}

tbody tr:focus-within {
    outline: 2px solid currentColor;
    outline-offset: -2px;
}
```

To only target the tables `TableTemplate` renders, give them a class with a `CssClassProvider` (see
[appearance.md](appearance.md#css-classes)) and use it in the selector:

```kotlin
CssClassProvider { part, element, _, _ ->
    if (part == UiPart.ModelTable && element == "table") setOf("model-table") else emptySet()
}
```

```css
table.model-table tbody tr:hover { background: rgba(0, 0, 0, 0.05); cursor: pointer; }
```

Anything beyond this is a good reason to build the page yourself: read the collection with your own query and render
it however you like.

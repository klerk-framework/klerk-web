# Appearance

Klerk-web produces semantic HTML and no styling of its own. Bring your own CSS.

## Layout

`Layout` produces whole documents: the `lang` attribute, `<title>`, a viewport meta tag and the stylesheet.

```kotlin
val layout = Layout(css = CssAsset("water.css"), assetsBase = pathProvider.assetsBase)
```

A `CssAsset` must be registered with the [AssetsPlugin](assets.md); its URL contains a content hash that is computed
when the application starts. `assetsBase` must match the `PathProvider` used for the same pages.

Or, for a stylesheet you do not serve yourself:

```kotlin
val layout = Layout(externalCssPath = "https://example.com/classless.css", lang = "sv")
```

`extraHead` is rendered last in `<head>`, for your own meta tags, icons or scripts:

```kotlin
Layout(css = css, extraHead = { link(rel = "icon", href = "/favicon.svg") })
```

Give the `Layout` to the `WebSupport` and every generated page uses it, the Admin UI included.

Writing your own pages? `Reader.html` lets you write the whole document yourself, `Layout.page(title) { ... }` gives
you the same `<head>` as the generated pages.

## CSS classes

Start with a [classless CSS](https://github.com/dbohdan/classless-css) - the generated HTML is designed for it.

When you need classes, give the `WebSupport` a `CssClassProvider`. It is called for every element klerk-web renders,
and one provider covers every block:

```kotlin
val classProvider = CssClassProvider { part, element, property, model ->
    when {
        part == UiPart.ModelTable && element == "table" -> setOf("striped")
        part == UiPart.Form && element == "input" -> setOf("form-control")
        else -> emptySet()
    }
}
```

* `part` is `ModelTable`, `ModelDetails` or `Form`.
* `element` is the HTML element name, e.g. `"table"`, `"td"`, `"input"`.
* `property` is the model or parameter property the element belongs to, when there is one.
* `model` is the model being rendered, when there is one.

Return an empty set to leave an element unstyled.

There is no theming system beyond this. If a page needs a different structure rather than different classes, build
that page yourself - the [building blocks](introduction.md#building-blocks) are meant to be replaced one at a time.

# Assets

Klerk-web can help you serve static CSS and JavaScript assets efficiently and in a type-safe manner.

The assets will be served with proper caching headers.
If the 'brotli' command line tool is installed, it will be used to serve Brotli-compressed text assets.

## CSS and JavaScript

Follow these steps:
1. Place your non-minified CSS and JavaScript files somewhere under the `src/main/resources/assets` directory.
2. Create a `CssAsset` or `JsAsset` instance for each file, specifying the path relative to `src/main/resources/assets`
   (no leading `/assets`). E.g. for a file at `src/main/resources/assets/matcha.css`:
   ```kotlin
   val css = CssAsset("matcha.css")
   val myScript = JsAsset("my-script.js")
   ```
3. Add the AssetsPlugin when creating the Klerk specification, e.g:
   ```kotlin
   SpecificationBuilder<Ctx, Views>(views).build {
   // lots of stuff here
   }.withPlugin(AssetsPlugin(setOf(css, myScript)))
   ```
4. Give the `CssAsset` to the [Layout](appearance.md), which renders the `<link>`. Asset URLs include a content hash
   for cache busting; `PathProvider.assetPath` builds them for anything else, such as a `JsAsset`:
   ```kotlin
   val pathProvider = DefaultPathProvider()
   val layout = Layout(css = css, assetsBase = pathProvider.assetsBase)
   ```
   ```kotlin
   call.respondHtml(block = layout.page("My page") {
       h1 { +"Hello" }
       script(pathProvider.assetPath(myScript.getPathAndHash())) { defer = true }
   })
   ```

The Admin UI lists the served assets with their compressed and uncompressed sizes on its
[plugins](plugins.md) page.

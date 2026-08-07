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
3. Add the AssetsPlugin when creating the Klerk configuration, e.g:
   ```kotlin
   ConfigBuilder<Context, Collections>(collections).build {
   // lots of stuff here
   }.withPlugin(AssetsPlugin(setOf(css, myScript)))
   ```
4. Asset URLs (which include a content hash for cache busting) are resolved through a `PathProvider`. Give it the
   `CssAsset` so it can build the `<link>` URL, and use `assetPath` for any `JsAsset`:
   ```kotlin
   val pathProvider = DefaultPathProvider(css = css)
   ```
   ```kotlin
   call.respondHtml {
       head {
           pathProvider.cssUrl()?.let { styleLink(it) }
       }
       body {
           script(pathProvider.assetPath(myScript.getPathAndHash())) { defer = true }
       }
   }
   ```

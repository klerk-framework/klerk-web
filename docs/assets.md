# Assets

Klerk-web can help you serve static assets (CSS, JS, images) efficiently and in a type-safe manner.

The assets will be served with proper caching headers.
If the 'brotli' command line tool is installed, it will be used to serve Brotli-compressed text assets.

## CSS and JavaScript

Follow these steps:
1. Place your non-minified CSS and JavaScript files somewhere in the `src/main/resources` directory. 
2. Create a `CssAsset` or `JsAsset` instance for each file, specifying the path relative to `src/main/resources`. E.g:
   ```kotlin
   val css = CssAsset("/assets/matcha.css")
   val myScript = JsAsset("/assets/my-script.js")
   ```
3. Add the AssetsPlugin when creating the Klerk configuration, e.g:
   ```kotlin
   ConfigBuilder<Context, Collections>(collections).build {
   // lots of stuff here
   }.withPlugin(AssetsPlugin(setOf(css, myScript)))
   ```
4. You can now use the assets in your HTML, e.g:
   ```kotlin
   call.respondHtml {
       head {
           styleLink(css)
       }
       body {
           script(myScript) { defer = true }
       }
   }
   ```
   The url for the asset is available as `myScript.url`.

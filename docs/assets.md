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

## Images

A picture that ships with the application — a logo, an illustration, a splash image — is an `ImageAsset`. Give the
plugin an [image plugin](images.md) as well and it is rendered through the same templates as an uploaded image, so a
4000 px photograph in the jar is not what a phone downloads:

```kotlin
val splash = ImageAsset("splash.jpg")
val hero = images.template("hero", widths = setOf(640, 1280, 2560), sizes = "100vw")

SpecificationBuilder<Ctx, Views>(views).build {
    // ...
}.withPlugin(images).withPlugin(AssetsPlugin(setOf(css, splash), images = images))
```

```kotlin
support.respondPage(call, "Welcome") {
    image(hero, splash, alt = "")
}
```

Variants are produced on demand and kept in the same `variantDirectory`, under the same budget and the same sweep as
uploaded images, so nothing new has to be configured for them. The URL carries the content hash —
`/_assets/splash.jpg_aB3xY/hero-1280.avif` — which is what lets a variant be cached forever; a redeploy that changes
the file changes the hash, and the sweep collects what the old one left behind.

Two things follow from an asset being part of the application rather than something a user owns. It is **public**: no
authorization is evaluated for it, and unlike attached data the original is always served. And it is **measured at
startup**, so `width` and `height` are rendered from the very first page and a static image never moves the layout.

An asset that is missing, that is not an image, or that is larger than `ImageLimits.maxPixels` fails the startup with
the asset named — a broken asset is a mistake by whoever wrote the application, not something to discover in
production.

Images are not Brotli-compressed: JPEG, PNG, WebP and AVIF already are.

The Admin UI lists the served assets with their compressed and uncompressed sizes on its
[plugins](plugins.md) page.

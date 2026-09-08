# Images

Serving an image in the size the page needs, rather than the size it was uploaded in. Sending a 4000 px photo to a
390 px viewport is where the bytes go; the format matters far less.

## Setup

```kotlin
val images = ImagePlugin<Context, MyViews>(
    variantDirectory = Path("/var/lib/myapp/variants"),
    processor = ImageIoProcessor(),
)

val specification = SpecificationBuilder<Context, MyViews>(views).build {
    // ...
}.withPlugin(images)

routing {
    attachedDataRoutes(support, images = images)
}
```

An image property is an ordinary `AttachedBlobContainer`. Nothing has to be declared on it:

```kotlin
class Portrait(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept = setOf("image/jpeg", "image/png")
}
```

Preparing it as it is attached is optional, and worth doing — it is where an oversized image is turned away before
it is stored, where the metadata a camera wrote is removed, and where the size a page needs to
[reserve space](#reserving-space) is learned:

```kotlin
class Portrait(id: AttachedBlobID) : AttachedBlobContainer(id) {
    override val accept = setOf("image/jpeg", "image/png")
    override val preAttachSteps = listOf(images::prepareImage)
}
```

See [what an image knows about itself](#what-an-image-knows-about-itself) for what that removes, and for
`prepareImageKeepExif` when you need it kept.

## What an image knows about itself

A photograph arrives knowing where it was taken, when, on what, sometimes who owns the camera, and often carrying a
thumbnail of an earlier crop of itself. None of that is what somebody uploading a profile picture meant to publish,
and it is personal data whether or not the application wanted it.

klerk-web deals with it in two places.

**The original is not served.** With the plugin registered, a request for an image without a variant segment is a
`404`: an image is reachable through a template, in the sizes that template declared, and a variant carries no
metadata. Turn that off when the original is the point — a photographer downloading their own work:

```kotlin
attachedDataRoutes(support, images = images, serveOriginalImages = true)
```

This is a serving decision, not a storage one. The bytes are untouched, so anything the application does with
`klerk.attachedData.get` itself still hands out whatever was uploaded.

**The stored file is cleaned.** `prepareImage` replaces the bytes with the same image, the right way up, holding
nothing but what a browser needs to display it. That is the one that protects every path, including your own code
and your backups, and it is what to declare unless something in the application genuinely needs the camera data:

| | |
|---|---|
| `images.prepareImage` | measures, refuses an image over `ImageLimits.maxPixels`, and stores it without metadata |
| `images.prepareImageKeepExif` | measures and refuses the same way, and keeps the file exactly as uploaded |

[`ImageProcessor.stripMetadata`](#writing-one) decides how, so the cost is the processor's choice: rewriting the
container keeps the quality the uploader chose, decoding and re-encoding is simpler and loses a little of it.
`ImageIoProcessor` does the latter.

The two differ in what they do when they cannot do their job. `prepareImageKeepExif` is a measurement, so an image
it cannot read is stored anyway and simply not measured. `prepareImage` is a promise, so an image it cannot
sanitise is refused with the reason — and a processor that is merely unreachable makes the step fail rather than
reject, so Klerk retries the upload instead of blaming the person who sent it.

Orientation is the one thing that is not dropped but applied: the pixels are written the way the camera was held, so
nothing downstream has to rotate them.

## Templates

A template is a role on a page, so it does not care whether the image was uploaded or ships with the application —
the same one serves both. See [assets](assets.md#images) for the static case.

Everything that decides how an image is served is a property of the slot it goes in, not of the image: how wide it
is rendered, which sizes are worth having, how urgently it is wanted. Declare each of those roles once:

```kotlin
val hero = images.template(
    "hero",
    widths = setOf(640, 1280, 2560),
    sizes = "100vw",
    loading = ImageLoading.Eager,
    fetchPriority = FetchPriority.High,
)
val thumbnail = images.template("thumbnail", widths = setOf(160, 320), sizes = "160px")
val avatar = images.template("avatar", widths = setOf(48, 96, 192), sizes = "48px")
```

`sizes` says how wide the image will be *rendered*, which is a fact about your CSS rather than about the image —
so it is the one thing klerk-web cannot work out for you.

Left out, it is `auto, 100vw`: the browser measures the image itself, and where it cannot, assumes the full
viewport width. That gets a page working without you having to think about it, and it is not free — until you say
otherwise, every browser that does not support `auto` fetches the largest variant of every image, including a
160 px thumbnail. So say otherwise for anything that is not full-width:

```kotlin
val thumbnail = images.template("thumbnail", widths = setOf(160, 320), sizes = "auto, 160px")
```

`auto` is only honoured on a lazily loaded image. An `Eager` template therefore defaults to plain `100vw` instead,
and writing `auto` on one yourself is refused; a single call that overrides `loading` to eager renders the fallback.
The fallback is not optional either: browsers that do not know `auto` skip that entry, and if nothing follows it
they use `100vw`. Write the width you would have written anyway and let `auto` improve on it where it is supported.

`loading` is `Lazy` unless you say otherwise. Say otherwise for the image that is visible without scrolling:
deferring that one delays the page's largest paint.

Small widths cost nothing. Nothing is generated until a browser asks for it, and an avatar rendered at 48 px never
causes a 2560 px file to exist — so give each role the ladder it actually wants rather than making them share.

## Rendering

Read the image's metadata inside a read block — the same metadata any
[attached-data URL](serving-attached-data.md) needs — so that the model and its image come from one snapshot:

```kotlin
val (flower, metadata) = klerk.read(context) {
    val flower = get(flowerID)
    flower to attachedData.metadata(flower.props.image.id)
}
```

Then render it in the role you want. `WebSupport` has to be in context, which it is inside `respondPage` —
elsewhere, use `with(support) { ... }`:

```kotlin
support.respondPage(call, flower.props.name.value) {
    image(hero, metadata, alt = "A flower")
}
```

That renders

```html
<img src="/_attached/42/ab12cd/hero-2560.jpeg"
     srcset="/_attached/42/ab12cd/hero-640.jpeg 640w, …/hero-1280.jpeg 1280w, …/hero-2560.jpeg 2560w"
     sizes="100vw"
     width="2560" height="1920" loading="eager" fetchpriority="high" decoding="async" alt="A flower">
```

A descriptor never promises more pixels than the file has: with a ladder of 640/1280/2560, an 800 px original is
offered at 640 only, and an image narrower than every width of the template is described by its real width. Until
the image has been measured the whole ladder is offered, so a browser can ask for a width the file cannot fill and
get a smaller one back.

Two things your stylesheet has to do, or responsive images do not work whatever the markup says:

```css
img { max-width: 100%; height: auto }
```

```html
<meta name="viewport" content="width=device-width">
```

Without the CSS, `width` renders every image at its widest variant. Without the viewport tag, a phone evaluates
`sizes` against a pretend 980 px viewport and fetches accordingly.

### Reserving space

An image that arrives after the page has been laid out pushes everything below it down. Whether that happens is a
question about your stylesheet, not about klerk-web.

**A box the CSS pins needs nothing.** Both dimensions, or a ratio, and the space is reserved before a byte arrives:

```css
.avatar { width: 40px; height: 40px; object-fit: cover }
.card-image { width: 100%; aspect-ratio: 16 / 9; object-fit: cover }
```

**A fluid image needs the `width` and `height` attributes.** With `height: auto` the browser has a width and needs
the image's proportions to work out a height, and it takes them from those attributes.

**A cropped template supplies them by itself.** A `crop` is a ratio you declared, true of every image that goes
through the template, so the attributes are rendered whether or not anything has measured the file. An avatar cut to
`Crop(1, 1)` is square from its first render.

An uncropped template is the one case left: its height follows the image's own proportions, so the attributes appear
only once the image has been measured, and until then it moves the page the first time it is shown.

`prepareImage` also refuses an image with more pixels than `ImageLimits.maxPixels`, with the size in the reason the
uploader sees. That is the only point at which the cap can be applied before the bytes are stored — without the step
an image nobody can decode is accepted, and is refused every time somebody asks for a variant of it instead. An
image it cannot measure at all is stored rather than refused: a transformer that is down would otherwise turn away
every upload while it is unreachable.

Measuring happens either way. The first variant that is generated writes the size down, so from then on every page
has it. `prepareImage` closes the window before that: the moments between the upload and the first generated
variant, which is exactly the page the person who uploaded is looking at. Declare it when the height of a slot
follows the image; skip it when your CSS pins the box, and save a decode on every upload.

What is known about an image is node-local, so on more than one node `prepareImage` helps the node that took the
upload, and the others render without the attributes until each has generated a variant of its own.

### Formats

With more than one format configured, a template renders a `<picture>` with a `<source type="image/…">` per
format and lets the browser choose. Each format is its own URL, so nothing has to vary on `Accept` and a CDN caches
cleanly. With one format — the default — it is a plain `<img>`.

```kotlin
ImagePlugin(variantDirectory = ..., formats = setOf("avif", "jpeg"), processor = myTransformer)
```

That set is the default. A template can lock itself to something else, which is how a logo keeps its transparency in
an application whose photographs are AVIF:

```kotlin
val logo = images.template("logo", widths = setOf(120, 240), sizes = "120px", formats = setOf("png"))
```

JPEG has no alpha channel, so anything transparent served as JPEG is flattened onto white. AVIF, WebP and PNG keep it.

A template's formats apply to it and to every art-directed alternative it declares — the alternatives are the same
image at another crop, so serving those in other formats would mean nothing. They need not be a subset of the
plugin's set, only of what the processor can write, and a format a template does not declare is a `404` for that
template even when another template offers it.

### Crops

`crop` is a shape, not a size — the width still comes from the ladder. It is how an avatar stays square whatever was
uploaded:

```kotlin
val avatar = images.template("avatar", widths = setOf(48, 96, 192), sizes = "48px", crop = Crop(1, 1))
```

The largest rectangle of that shape is kept, placed where the `gravity` says: `Crop(4, 5, gravity = Gravity.North)`
for portraits, since faces are near the top. Nothing is ever enlarged to fill the shape, and the crop is taken after
the image has been turned the right way up, so it cuts the edges the viewer sees rather than the edges the sensor
recorded.

**Why not `object-fit: cover`?** Because it breaks the size negotiation. `sizes` says how wide the *element* is, and
the browser multiplies that by the device pixel ratio to pick from `srcset`. Under `cover` the file has to be bigger
than the element by the ratio between the image's shape and the box's — and that factor arrives with each upload.

A 48 px square avatar at DPR 2 needs 96 device pixels. Fed a 4:3 photo, `cover` scales until the height covers the
box, so the file needs 128 px of width for 96 to survive; a 16:9 photo needs 171; a portrait needs 96 and no
correction at all. No single `sizes` is right for all three, so some of them come out soft. Cropping on the server
removes the problem instead of compensating for it: the file *is* the box's shape, so `sizes = "48px"` means what it
says, and the `srcset` descriptors describe the cropped file.

**Use CSS when the shapes are close.** If what people upload is roughly the shape of the slot, the correction factor
is near 1, nothing comes out soft, and `object-fit: cover` costs nothing. A crop is not free: each one is its own set
of files — `Crop(1, 1)` at three widths and two formats is six more per image, charged against `maxVariantBytes` —
and changing a template's crop changes the filenames, so the old files are not recognised as replaced and linger
until the data is deleted or the budget evicts them.

So: crop on the template when the slot's shape differs materially from what people upload — avatars, a 16:9 hero fed
by phone photographs — and let CSS do it when it does not.

### Art direction

A phone showing a 16:9 hero gets a letterbox strip. Art direction is giving it a different crop rather than a
smaller copy of the same one:

```kotlin
val hero = images.template("hero", widths = setOf(640, 1280, 2560), sizes = "100vw", crop = Crop(16, 9)) {
    on("mobile", media = "(max-width: 600px)", widths = setOf(320, 640), sizes = "100vw", crop = Crop(4, 5))
}
```

Each alternative is served under the template's name and its own: `/_attached/42/ab12cd/hero-mobile-320.jpeg`. The
browser takes the first one whose `media` holds, so declare the narrowest first, and the default is what it uses
when none of them do.

```html
<picture>
  <source media="(max-width: 600px)" type="image/jpeg" srcset="…/hero-mobile-320.jpeg 320w, …"
          sizes="100vw" width="320" height="400">
  <img src="…/hero-2560.jpeg" srcset="…" sizes="100vw" width="2560" height="1440" …>
</picture>
```

Every `<source>` carries its own `width` and `height`: the browser lays out from the source it picked, so without
them the phone reserves the desktop's shape and the page jumps when the image arrives.

Nothing about the crop is per image — it is a ratio and a gravity, worked out at generation time. A photograph whose
subject is off to one side is cropped the same as any other.

### Everything else is CSS

Size, shape and format are all klerk-web transforms, because they decide how many bytes travel. Nothing else does.
Blur, grayscale, saturation, contrast, rounded corners, rotation, opacity — `filter` and `border-radius` do all of
them in the browser, instantly, reversibly, and without generating a single file. Doing them here would not save a
user one byte; it would only multiply the variants, and the number of files klerk-web can be made to generate is
exactly what bounds its appetite for disk and CPU.

There are three places a change to an image can happen, and each has one job:

| | |
|---|---|
| a [pre-attach step](https://github.com/klerkframework/klerk/blob/main/docs/attached-data.md) | changes the stored file, once and irreversibly — stripping EXIF, re-encoding, a watermark that has to survive somebody saving the file |
| a template | changes what is delivered: size, shape, format |
| CSS | changes what is painted |

If a transformation does not change how many bytes travel, and does not have to survive the file leaving your site,
it belongs in the stylesheet.

## The allow-list

A variant is the last segment of an ordinary [attached-data URL](serving-attached-data.md), and names the template
that asked for it: `/_attached/42/ab12cd/hero-640.jpeg`.

The registered templates are the allow-list the route enforces. A width the template does not offer is a `404`; a
template nobody declared is not a variant request at all, and is served as the download the URL otherwise means.
That the list is finite is the point: a client that could ask for any width could make the server generate images
until the disk filled up.

Variants are stored by width and format, not by template, so two roles that share a width share the file.

## What happens on a miss

The first request for a size that has not been generated yet schedules a job and **waits for it**, up to `renderWait`
(five seconds by default). What comes back under a variant's URL is always that variant, so every successful response
is cacheable and a CDN can sit in front of the whole route.

Nothing stands in for a missing variant. Serving the original instead would be the wrong size, the wrong shape for a
cropped template, and — for an image no processor will ever accept — an unbounded amount of egress on every request,
since such a response can never be cached.

| | |
|---|---|
| the variant exists | it is served, cached like any other |
| it is generated within `renderWait` | the same |
| the processor refused the image | `404`, immediately on every later request |
| the queue has not got to it | `503` with `Retry-After`, `no-store` |

A `503` means the application is overloaded, not that the image is cold, so set `renderWait` with the render queue in
mind: a page of cold images serialises through `maxConcurrentRenders`, which is 2 by default.

Scheduling is single-flighted: a hundred simultaneous misses produce one job, not a hundred commands on the single
writer. All hundred wait for that one job.

A refusal is remembered. An image the processor will not decode is refused once and answered `404` from then on,
rather than costing a held connection every time somebody asks for it.

The generated files, and a small `meta.json` holding the image's real dimensions — written by `prepareImageKeepExif` at upload
time, or by the generator otherwise — live under `variantDirectory`. It is a **cache**: it can be deleted at any time and
costs only regeneration, so keep it out of your backups. It is also node-local — an application on several nodes has
one per node, and until a node has generated or been told the dimensions, its pages render without `width`/`height`.
A reconciling job (`klerk-web-image-sweep`, hourly) deletes what belongs to attached data that has been deleted or
replaced.

### A budget for the directory

Nothing bounds how much that directory holds unless you say so. `maxVariantBytes` is the cap; past it, the same
sweep evicts whole images, oldest-generated first, until it is back under:

```kotlin
ImagePlugin(variantDirectory = ..., processor = ..., maxVariantBytes = 20L * 1024 * 1024 * 1024)
```

Whole images rather than single files, so that a `meta.json` is never dropped while its variants stay — that would
silently cost every page the image's dimensions. Eviction happens only in the sweep, never while serving, because an
evicted variant costs the next request for it a render it has to wait for. For the same reason the budget wants to
be generous: it is a backstop against filling the disk, not a working set.

## What it reports

The plugin publishes to the `MeterRegistry` in `KlerkSettings`. Because a request waits for the variant it asked
for, the render queue sits on the critical path of a page load, and these are what say whether it is keeping up:

| | |
|---|---|
| `klerk.web.image.render` | timer: how long one variant takes to produce, and how many there have been |
| `klerk.web.image.wait` | timer: how long a request waited for a variant that did not exist yet |
| `klerk.web.image.unavailable` | counter: requests that gave up waiting and were answered `503` |
| `klerk.web.image.refused` | counter: images the processor would not decode |
| `klerk.web.image.bytes` | gauge: what the variant directory currently holds |

`wait` and `unavailable` are the pair to watch. Waits creeping towards `renderWait`, or any sustained `unavailable`,
mean `maxConcurrentRenders` is too low for the load rather than that `renderWait` is too short.

## Processors

`ImageProcessor` is what actually scales. `processor` has no default and never will: it decides where an image a
stranger uploaded gets decoded, and that is not a choice to make on your behalf.

klerk-web ships one implementation, and it is not the one to run in production.

### ImageIoProcessor

Needs nothing installed, which is the whole point of it. It writes JPEG and PNG, turns a photograph the right way up
— a phone records how it was held rather than rotating the pixels — and measures it as it will be displayed, so a
portrait photo gets a portrait `width`/`height`. Transparency survives into a PNG and is flattened onto white for a
JPEG, which has no alpha channel.

It logs a warning when you construct it, because three things are true of it:

- it decodes bytes an attacker chose inside your own JVM, where a decoder bug is your process;
- it writes neither WebP nor AVIF;
- `ImageLimits.timeout` frees the coroutine but cannot interrupt a decode already running inside ImageIO, so
  `maxPixels` is the protection that actually bites.

Downscaling is where nearly all of the bytes are, so this is a real answer while you are building and for an
application nobody has a reason to attack. Move off it before that changes.

### Writing one

The production answer is an image transformer running beside the application — its own process, its own container,
no network egress — so that a decoder bug is contained and modern formats come for free. Several fit this shape:
imagor, imgproxy and thumbor among them. klerk-web does not choose between them and does not bundle one; they all
work the same way, so the implementation is short whichever you pick.

```kotlin
class TransformerProcessor(
    private val baseUrl: String,
    override val limits: ImageLimits = ImageLimits(),
) : ImageProcessor {

    override val outputFormats: Set<String> = setOf("jpeg", "png", "webp", "avif")

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = limits.timeout.inWholeMilliseconds }
    }

    private data class Metadata(val width: Int, val height: Int)

    override suspend fun verify() {
        val answer = runCatching { client.get("$baseUrl/health") }.getOrNull()
        checkNotNull(answer?.takeIf { it.status.isSuccess() }) { "The image transformer at $baseUrl did not answer" }
    }

    override suspend fun probe(source: Path): ImageInfo? {
        val response = client.get("$baseUrl/meta/${source.fileName}")
        if (!response.status.isSuccess()) {
            return null
        }
        val meta = Gson().fromJson(response.bodyAsText(), Metadata::class.java)
        return ImageInfo(meta.width, meta.height)
    }

    override suspend fun stripMetadata(source: Path, target: Path): ImageInfo {
        val response = client.get("$baseUrl/strip/${source.fileName}")
        when {
            response.status.isSuccess() -> Unit
            response.status.value in 400..499 -> throw ImageRefused("$source was refused: ${response.status}")
            else -> error("The image transformer answered ${response.status}")
        }
        response.bodyAsChannel().toInputStream().use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return ImageInfo(
            checkNotNull(response.headers["X-Source-Width"]).toInt(),
            checkNotNull(response.headers["X-Source-Height"]).toInt(),
        )
    }

    override suspend fun render(source: Path, target: Path, width: Int, format: String, crop: Crop?): ImageInfo {
        val shape = crop?.let { "/crop/${it.width}x${it.height}/${it.gravity}" } ?: ""
        val response = client.get("$baseUrl/fit/$width$shape/${source.fileName}@$format")
        when {
            response.status.isSuccess() -> Unit
            // The transformer enforces the pixel cap, so 4xx covers "too big to decode" as well as "not an image".
            response.status.value in 400..499 -> throw ImageRefused("$source was refused: ${response.status}")
            // Down, restarting, out of workers: worth trying again.
            else -> error("The image transformer answered ${response.status}")
        }
        response.bodyAsChannel().toInputStream().use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return ImageInfo(
            checkNotNull(response.headers["X-Source-Width"]).toInt(),
            checkNotNull(response.headers["X-Source-Height"]).toInt(),
        )
    }
}
```

Six things to get right. The rest is your transformer's URL syntax.

**Hand it the file, not a URL.** Set `stagingDirectory` on the plugin to a directory the transformer also mounts, and
point its file loader at the same place; klerk-web puts the original there before calling you. Do not let the
transformer fetch the original from your own application over HTTP instead — that is a second, unauthenticated route
to the originals and an SSRF surface in one, and it bypasses the `readAttachedData` rules that are the reason this
route exists. Turn the transformer's network egress off entirely, and sign its URLs if it can.

**Let the transformer enforce the pixel cap.** Configure its own maximum source resolution to match
`limits.maxPixels` and map its refusal to `ImageRefused`. Nothing checks `limits` for you — they are what stands
between a 1 kB file and an exhausted heap — but checking them by decoding the header yourself puts you back in the
business of parsing hostile files, which is what you are paying a sidecar to avoid.

**`ImageRefused` means never; anything else means later.** Klerk dead-letters the variant's job immediately on
`ImageRefused`: the source is not an image, or is bigger than you will decode, and no retry changes that. Every other
exception leaves the job retryable, which is where a transformer that is down, restarting or timing out belongs.
Report a restarting container as `ImageRefused` and every image that happened to be in flight is permanently marked
broken.

**One roundtrip.** `render` returns the size of the original, so generating a variant does not also call `probe`. If
your transformer can report the dimensions alongside the bytes — a response header, say — a variant costs one
request. If it only has a separate metadata endpoint, call it; that is a property of the transformer rather than of
this interface. `probe` is what runs while an image is being uploaded, where there is nothing to render yet.

**Fail at startup.** `verify()` runs once when the application starts. Use it, so that an unreachable transformer or
a wrong secret stops the boot rather than turning into images that silently never appear an hour later.

**Turn its own cache and result storage off.** `VariantStore` already caches, sweeps and forgets. A second copy of
personal data, with its own retention and nobody's erasure job pointed at it, is a liability rather than a speed-up.

One thing to size: `maxConcurrentRenders` bounds generation, but `prepareImageKeepExif` runs once per upload and is not
bounded by it. The transformer's own worker count is what bounds that.

### Limits

Every implementation must honour its `ImageLimits`:

```kotlin
ImageIoProcessor(limits = ImageLimits(maxPixels = 20_000_000, timeout = 15.seconds))
```

`maxPixels` is checked before anything is decoded, because a 1 kB PNG can declare 100 megapixels and decoding it is
how a small file becomes a large heap. `timeout` is advisory for `ImageIoProcessor` — a decode already running inside
ImageIO cannot be interrupted — and real for anything out of process, which can be given up on.
`maxConcurrentRenders` on the plugin bounds how many images are being processed at once, and `renderWait` bounds how
long a request will wait for its turn.

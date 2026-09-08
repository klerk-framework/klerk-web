# Serving attached data

`attachedDataRoutes` serves [attached data](https://github.com/klerkframework/klerk/blob/main/docs/attached-data.md)
— blobs and strings alike — over HTTP, authorized by Klerk's own rules.

```kotlin
routing {
    attachedDataRoutes(support)
}
```

| | |
|---|---|
| `GET {path}/{id}/{hash}` | the value |
| `GET {path}/{id}/{hash}/{filename}` | the same; the last segment is ignored, so a download can have a human name |

Build the URLs with `PathProvider.attachedDataPath`. It needs the value's metadata, which carries the id and the hash
the URL is made of — read it on the reader inside the read block, or with `klerk.attachedData.getMetadata(id, context)`
outside one:

```kotlin
val src = klerk.read(context) {
    pathProvider.attachedDataPath(attachedData.metadata(get(flowerID).props.image.id))
}
```

```kotlin
img(src = src) { alt = "A flower" }
```

The [model detail page](model-pages.md) links attached properties to this route by itself.

## What the route decides for you

**The hash is part of the URL's identity.** Ids are recycled once the data they referred to has been deleted, so an
id alone is not a safe cache key — a cached response could end up serving one value under another value's URL. A
request whose hash does not match the data gets a `404`.

**Authorization is Klerk's own.** The `readAttachedData` rules are evaluated against the model that owns the value,
without reading a byte of it. Data that does not exist, a hash that does not match, and data this actor may not read
all give the same `404`, so the route cannot be used to find out what exists.

**Caching follows visibility.** By default, `Public` data is `public, max-age=2419200, immutable` — 28 days (influenced 
by ‘right to be forgotten’ in GDPR), and never
revalidated within them, because the content at a hash-addressed URL cannot change. `Private` data is `private,
no-store`, this is the safest default (a user that no longer has access to the data is not going to see it), but it is
recommended to consider using a more generous lifetime.

The header is decided per value by `WebSupport.attachedDataCacheControl`, which is handed the metadata:

```kotlin
WebSupport(
    klerk = klerk,
    contextProvider = ::context,
    attachedDataCacheControl = { metadata ->
        when (metadata.visibility) {
            AttachedDataVisibility.Public -> "public, max-age=31536000, immutable"
            AttachedDataVisibility.Private -> "private, max-age=300, immutable"
        }
    },
)
```

Anything in `AttachedDataMetadata` can decide it — `custom` in particular, so the model that owns the value can say
what its caching should be at the point the value is attached.

**Nothing is rendered inline unless you said it may be.** A value is served with its own content type only if Klerk
recognised its bytes as one of `inlineContentTypes` — by default PNG, JPEG, GIF, WebP and AVIF. Everything else is
`application/octet-stream` with `Content-Disposition: attachment`, and `X-Content-Type-Options: nosniff` is always
sent. SVG and HTML are deliberately not in the default set: served inline from your own origin, they are a script
running as your application.

Install Ktor's `PartialContent` plugin to have range requests served.

## Images

With the [image plugin](images.md) registered, the last segment can ask for a size instead of naming a download —
`/_attached/42/ab12cd/hero-640.jpeg` — and the route serves the image scaled to that width.

It also stops serving the **originals** of the types that plugin handles: an image is reachable through a template, in
the sizes that template declared, because the original is the copy that still knows where the photograph was taken.
Pass `serveOriginalImages = true` when the original is the point, and strip the file at upload with `prepareImage`
when you do. See [what an image knows about itself](images.md#what-an-image-knows-about-itself).

## Behind a CDN

Public data is a good fit: the URL contains the hash, so it never changes meaning, and no authorization rule is
evaluated for it. Private data is not — it has a hit rate of roughly zero at a shared cache, and getting it wrong
leaks one user's data to another. Keep the CDN out of the path for it.

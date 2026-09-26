# Caching of pages

Klerk-web sets `Cache-Control` on what it serves from its own routes: [assets](assets.md) and
[attached data](serving-attached-data.md). Pages are yours, and so is their caching.

A page shown to a logged-in user holds that user's live data. Send it with `Cache-Control: private, no-store` (or
`Cache-Control: private, no-cache` if that is acceptable).

Use Ktor's [CachingHeaders](https://ktor.io/docs/server-caching-headers.html) plugin
(`io.ktor:ktor-server-caching-headers`):

```kotlin
install(CachingHeaders) {
    options { call, content ->
        val isHtml = content.contentType?.match(ContentType.Text.Html) == true
        val hasOwn = call.response.headers.contains(HttpHeaders.CacheControl)
        if (isHtml && !hasOwn && call.sessions.get<UserSession>() != null) {
            CachingOptions(CacheControl.NoStore(CacheControl.Visibility.Private))
        } else {
            null
        }
    }
}
```

Replace the `UserSession` check with however your application tells a logged-in user apart. The `hasOwn` check keeps
the header of a response that already has one, e.g. an uploaded HTML file served as attached data, which would
otherwise get two conflicting `Cache-Control` headers.

Using htmx? Its history cache restores pages from `localStorage` on back and forward, without asking the server. Turn
it off so that going back loads the page again:

```html
<meta name="htmx-config" content='{"historyCacheSize":0,"refreshOnHistoryMiss":true}'>
```

# Uploads

Resumable file upload, ending in [attached data](https://github.com/klerkframework/klerk/blob/main/docs/attached-data.md)
owned by one of your models.

## Setup

```kotlin
val uploads = UploadPlugin<Context, MyViews>(stagingDirectory = Path("/var/lib/myapp/uploads"))

val config = ConfigBuilder<Context, MyViews>(views).build {
    persistence(SqlPersistence(dataSource))
    attachedBlobStore(FileBlobStore(Path("/var/lib/myapp/blobs")))
    // ...
}.withPlugin(uploads)

routing {
    uploadRoutes(support, uploads)
}
```

The staging directory is where partial uploads accumulate. It is independent of where blobs end up — an application
keeping blobs in the database can still upload large files — but putting it on the same filesystem as a
`FileBlobStore` makes the last step a rename instead of a copy.

Then declare the field on a form with [`file()`](forms.md#files). That is the whole integration; the rest of this page
is what happens underneath and what you can change.

## How an upload works

An upload is a model. That is what gives it an owner, an authorization rule, an audit trail and a time trigger that
cleans it up when it is abandoned.

1. The browser starts an upload. `CreateUpload` runs **as the user**, carrying the filename, the content type and the
   declared size — so your authorization rules decide whether it may begin, before a single byte is accepted.
2. Chunks arrive and are appended to a staging file named after the upload's model id. Each chunk is flushed to disk
   before the model records it, so the recorded offset is never larger than what is actually there: an interrupted
   upload costs the client a re-sent chunk, never a lost one.
3. When the last declared byte arrives, the upload becomes `Ready`.
4. The form is submitted with the upload's id. `parse` hands the staged file to `prepare` and puts the resulting
   `AttachedBlobID` in the parameters; your command stores it on a model and owns it from then on.

There is no state for "attached": once the bytes are a blob, the blob and its owning model are the record of it, and
the upload is deleted.

## The protocol

The endpoints speak [tus 1.0](https://tus.io/protocols/resumable-upload), so an off-the-shelf client works — though
the script klerk-web ships with its forms needs no dependency.

| | |
|---|---|
| `POST /uploads` | starts one. `Upload-Length` and `Upload-Metadata` describe it; a small file may come with it. |
| `HEAD /uploads/{id}` | the current `Upload-Offset` — where to resume. |
| `PATCH /uploads/{id}` | appends at `Upload-Offset`. `409` (with the real offset) if that is not where the upload is. |
| `DELETE /uploads/{id}` | discards it. |

The checksum extension is supported: send `Upload-Checksum: sha256 <base64>` with a chunk and a corrupted one is
rejected with `460` and rolled back rather than stored.

## Limits and quotas

A limit that belongs to a *property* is declared on its `BlobContainer` and needs nothing here — the form tells the
endpoint which property the file is for, so `maxSize` is applied before any byte is accepted as well as when the
command attaches the value. On the path without JavaScript, where there is no upload to create, the limit is enforced
while the file is being copied, so an over-long file is cut off rather than stored and rejected later.

A limit that depends on *who is asking* is an ordinary authorization rule. The declared size is known before any byte
is accepted, so the rule can see it:

```kotlin
fun normalUsersCanUploadUpTo10MB(args: ArgCommandContextReader<*, Context, MyViews>): NegativeAuthorization {
    val params = args.command.params
    if (params !is CreateUploadParams) return Pass
    return if (params.context.isNormalUser && params.declaredSize.value > 10_000_000) Deny else Pass
}
```

The declared size is a claim, not a fact: the server stops at it while copying, so a client that lies about the length
of its body is cut off rather than allowed to fill the disk. `uploadRoutes` also takes a `maxSize` that no upload may
exceed, advertised to clients as `Tus-Max-Size`.

The same rule decides on both paths. Without JavaScript no upload is created — a single request has nothing to resume
— so klerk-web dry-runs `CreateUpload` before consuming the file part and reports the refusal as an ordinary form
problem. Note what "before" means on each path: with JavaScript the client is told `403` and sends nothing, while a
form submission has already begun transmitting by the time it can be authenticated, since the CSRF token is inside the
body. What is prevented there is storing the bytes, not receiving them.

## Security

- **An upload may only be continued by the actor that started it.** One that does not exist and one that belongs to
  somebody else give the same `404`, so the endpoints cannot be used to find out which uploads exist.
- **Every mutating request carries a CSRF token**, as a header rather than a form field.
- **The filename and content type are the client's claims**, kept as metadata and never used as fact. The filename is
  never used as a path — staging files are named after the model id. When serving, use `metadata.contentType`, which
  is what Klerk recognised the bytes to be, together with `X-Content-Type-Options: nosniff` and
  `Content-Disposition: attachment` for anything you have not deliberately allowed inline. Never serve user-supplied
  SVG or HTML inline from the same origin as your application.
- **What the file may be is declared on the property**, not here. `accept`, `maxSize` and `inspect` on the
  [`BlobContainer`](https://github.com/klerkframework/klerk/blob/main/docs/attached-data.md) are checked when the
  command attaches the value, so they hold for uploads and for every other caller. This plugin only applies `maxSize`
  early, as a courtesy, so that a file that cannot be used is not uploaded first.

## Cleaning up

An upload that is never finished, or finished and never submitted, is deleted after `lifetime` (24 hours by default).
The staging file it leaves behind is removed by the plugin's own job, `klerk-web-upload-sweep`, which reconciles the
directory against the models — by reconciliation rather than by a hook, so bytes left by a crash are cleaned up too.
It runs hourly; pass `sweepExpression` to the plugin for a different cron expression.

A submitted upload's bytes are handed to attached data straight away, and its model is deleted with them.

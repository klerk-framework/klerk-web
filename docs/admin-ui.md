# Admin UI


## Tools

### Admin-UI

The Admin-UI provides a web-interface to manage your system. To use it, you first need to create
a `LowCodeMain`:
```kotlin
val LowCodeMain = LowCodeMain(
    basePath = "/admin",
    contextProvider = ApplicationCall::ctx,
    showOptionalParameters = { eventReference -> false },
    cssPath = "https://unpkg.com/almond.css@latest/dist/almond.min.css",
)
```

Then you use the config to register the routes:
```kotlin
    routing {
        apply(LowCodeMain(klerk, LowCodeMain).registerRoutes())
        // other routes
    }
```

When you run your application, you can access the admin-ui at `/admin`.

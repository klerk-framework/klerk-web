# Admin UI


## Tools

### Admin-UI

The Admin-UI provides a web-interface to manage your system. To use it, you first need to create
a `LowCodeConfig`:
```kotlin
val lowCodeConfig = LowCodeConfig(
    basePath = "/admin",
    contextProvider = ApplicationCall::ctx,
    showOptionalParameters = { eventReference -> false },
    cssPath = "https://unpkg.com/almond.css@latest/dist/almond.min.css",
)
```

Then you use the config to register the routes:
```kotlin
    routing {
        apply(LowCodeMain(klerk, lowCodeConfig).registerRoutes())
        // other routes
    }
```

When you run your application, you can access the admin-ui at `/admin`.

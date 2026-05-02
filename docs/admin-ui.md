# Admin UI


## Tools

### Admin-UI

The Admin-UI provides a web-interface to manage your system. To use it, you first need to create
a `LowCodeMain`:
```kotlin
adminUI = AdminUI(
    klerk,
    "/admin",
    ApplicationCall::ctx,
    cssPath = css.url,
    canSeeAdminUI = ::canSeeAdminUI,
    autoButtons = autoButtons
)
```

Then you use the config to register the routes:
```kotlin
    routing {
        apply(adminUI.registerRoutes())
        // other routes
    }
```

When you run your application, you can access the admin-ui at `/admin`.

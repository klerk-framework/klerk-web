# Admin UI

The Admin-UI provides a web-interface to manage your system.

## Initializing
First you need to:
1. create a context provider (see [Introduction](introduction.md))
2. create an AutoButtons instance (see [AutoButtons](auto-buttons.md))

Then you can initialize the AdminUI:
```kotlin
val pathProvider = DefaultPathProvider(prefix = "admin/")
adminUI = AdminUI(
    klerk,
    ApplicationCall::ctx,
    canSeeAdminUI = ::canSeeAdminUI,
    autoButtons = autoButtons,
    pathProvider = pathProvider,
)
```
`pathProvider` controls where the admin routes are mounted (here, under `/admin/`) and where CSS/JS assets are
resolved from (see [Assets](assets.md)). `canSeeAdminUI` is called on every request to authorize access.

Then you use the config to register the routes:
```kotlin
    routing {
        apply(adminUI.registerRoutes())
        // other routes
    }
```

When you run your application, you can access the admin-ui at `/admin`.

# Plugins in the Admin UI

This section is only relevant for someone who creates a Klerk plugin.

A Klerk plugin can give itself a page in the [Admin UI](admin-ui.md) and register routes of its own by implementing
`AdminUIPluginIntegration`. Klerk-web's own [assets](assets.md) plugin is built this way.

```kotlin
class MyPlugin<C : KlerkContext, V> : AdminUIPluginIntegration<C, V> {

    override val name: String = "My plugin"
    override val description: String = "What it does"

    override val page: PluginPage<C, V> = object : PluginPage<C, V> {
        override val buttonText: String = "My plugin"

        override suspend fun respond(call: ApplicationCall, support: WebSupport<C, V>, klerk: Klerk<C, V>) {
            val context = support.contextProvider(call, klerk)
            call.respond(klerk.read(context) {
                html {
                    body { h1 { +"My plugin" } }
                }
            })
        }
    }

    override fun registerExtraRoutes(routing: Routing, pathProvider: PathProvider) {
        routing.get("${pathProvider.withPrefix()}my-plugin/something") {
            // ...
        }
    }
}
```

The plugin gets a button in the Admin UI's navigation and is listed on the plugins page. Its page is reached at
`{prefix}plugin?name={name}`.

`render` is given the Admin UI's `WebSupport`, so use `support.layout` to produce a page that matches the rest of
the console.

Plugins that do not implement `AdminUIPluginIntegration` are still listed on the plugins page, with their name and
description, but have no page of their own.

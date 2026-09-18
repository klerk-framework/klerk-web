package dev.klerkframework.web
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkPlugin
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import kotlinx.html.*

/**
 * A Klerk plugin that gives itself a page in the Admin UI and routes of its own. See
 * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/plugins.md).
 */
public interface AdminUIPluginIntegration<C : KlerkContext, V> : KlerkPlugin<C, V> {

    /** The plugin's page in the Admin UI. */
    public val page: PluginPage<C, V>

    /** Called when the Admin UI registers its routes. Mount them under [PathProvider.withPrefix]. */
    public fun registerExtraRoutes(route: Route, pathProvider: PathProvider)
}

/** A plugin's own page in the Admin UI. */
public interface PluginPage<C : KlerkContext, V> {
    /** The text of the button in the Admin UI's navigation. */
    public val buttonText: String

    /** Responds with the page. Use [WebSupport.layout] so it matches the rest of the console. */
    public suspend fun respond(call: ApplicationCall, support: WebSupport<C, V>, klerk: Klerk<C, V>)
}

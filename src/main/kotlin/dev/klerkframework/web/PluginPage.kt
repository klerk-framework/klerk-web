package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*

internal suspend fun <C : KlerkContext, V> renderPluginPage(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    val pluginName = requireNotNull(call.request.queryParameters["name"])
    val plugin = klerk.spec.plugins.single { it.name == pluginName } as AdminUIPluginIntegration<C, V>
    plugin.page.respond(call, support, klerk)
}

package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderPlugins(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    support.respondPage(call, "Plugins") {
            header {
                nav { div { a(href = support.pathProvider.withPrefix()) { +"Home" } } }
            }
            h1 { +"Plugins" }
            main {
                val plugins = klerk.specification.plugins
                if (plugins.isEmpty()) {
                    p { +"No plugins" }
                } else {
                    ul {
                        plugins.forEach { plugin ->
                            li {
                                if (plugin is AdminUIPluginIntegration<C, V>) {
                                    a(href = "${support.pathProvider.withPrefix()}plugin?name=${plugin.name}") { +plugin.name }
                                } else {
                                    +plugin.name
                                }
                                br()
                                i { +plugin.description }
                                br()
                            }
                        }
                    }

                }
            }
    }
}

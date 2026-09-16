package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderLog(
    call: ApplicationCall, support: WebSupport<C, V>, klerk: Klerk<C, V>,
) {
    val context = support.contextProvider.invoke(call, klerk)
    support.respondPage(call, "Activity log") {
            header {
                nav { div { a(href = support.pathProvider.withPrefix()) { +"Home" } } }
            }
            main {
                h1 { +"Activity log" }
                for (entry in klerk.activityLog.entries(context)) {
                    // the LogEntry is currently always a fat object (i.e. it contains lots of interesting stuff). But
                    // soon we will persist the log, which means that after a restart, the entries will not be fat
                    // anymore. So we can only use the template and facts here. (but for now we have to use
                    // toLogMessage())

                    details {
                        summary {
                            +"${formatTime(entry.time)} ${entry.heading}"
                        }
                        table {
                            entry.content?.let {
                                tr {
                                    td { +"Content" }
                                    td { +it }
                                }
                            }
                            tr {
                                td { +"Actor" }
                                td { +(entry.actor?.toString() ?: "[none]") }
                            }
                            tr {
                                td { +"Source" }
                                td { +entry.source.toString() }
                            }
                            for (fact in entry.facts) {
                                tr {
                                    td { +fact.type.name }
                                    td { +"${fact.name}=${fact.value}" }
                                }
                            }
                        }


                    }
                    br()

                }


            }
    }
}

package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.micrometer.core.instrument.Measurement
import io.micrometer.core.instrument.Meter
import kotlinx.html.*
import java.util.Locale

internal suspend fun <C : KlerkContext, V> renderMetrics(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>,
) {
    val meters = klerk.settings.meterRegistry.meters
        .sortedWith(compareBy<Meter>({ it.id.name }, { it.id.tags.joinToString() }))
    support.respondPage(call, "Metrics") {
        header {
            nav { div { a(href = support.pathProvider.withPrefix()) { +"Home" } } }
        }
        main {
            h1 { +"Runtime" }
            val runtime = Runtime.getRuntime()
            table {
                tr {
                    td { +"Available processors" }
                    td { +runtime.availableProcessors().toString() }
                }
                tr {
                    td { +"Max memory (MB)" }
                    td { +(runtime.maxMemory() / 1000000).toString() }
                }
                tr {
                    td { +"Total memory (MB)" }
                    td { +(runtime.totalMemory() / 1000000).toString() }
                }
                tr {
                    td { +"Free memory (MB)" }
                    td { +(runtime.freeMemory() / 1000000).toString() }
                }
            }
            h1 { +"Metrics" }
            if (meters.isEmpty()) {
                p { +"No metrics have been registered." }
                return@main
            }
            table {
                thead {
                    tr {
                        th { +"Name" }
                        th { +"Tags" }
                        th { +"Value" }
                        th { +"Base unit" }
                        th { +"Description" }
                    }
                }
                tbody {
                    for (meter in meters) {
                        tr {
                            td { +meter.id.name }
                            td { +meter.id.tags.joinToString(", ") { "${it.key}=${it.value}" } }
                            td {
                                meter.measure().forEachIndexed { i, measurement ->
                                    if (i > 0) br()
                                    +format(measurement)
                                }
                            }
                            td { +(meter.id.baseUnit ?: "") }
                            td { +(meter.id.description ?: "") }
                        }
                    }
                }
            }
        }
    }
}

private fun format(measurement: Measurement): String {
    val value = measurement.value
    val formatted = if (value.isFinite() && value == Math.rint(value)) {
        value.toLong().toString()
    } else {
        "%.3f".format(Locale.ROOT, value)
    }
    return "${measurement.statistic.name.lowercase()}: $formatted"
}

package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

/**
 * One row for [renderSettings]: a [dev.klerkframework.klerk.KlerkSettings] value, the dotted path used to look it up
 * in [dev.klerkframework.klerk.SettingsProvenance], and how to render it.
 */
private data class SettingRow(val label: String, val path: String, val value: String)

internal suspend fun <C : KlerkContext, V> renderSettings(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>,
) {
    val settings = klerk.settings
    val jobs = settings.jobs

    val rows = listOf(
        SettingRow("Persistence", "persistence", settings.persistence::class.simpleName ?: "?"),
        SettingRow(
            "Attached blob store",
            "attachedBlobStore",
            settings.attachedBlobStore?.let { it::class.simpleName } ?: "none",
        ),
        SettingRow("Clock", "clock", settings.clock::class.simpleName ?: "?"),
        SettingRow("Meter registry", "meterRegistry", settings.meterRegistry::class.simpleName ?: "?"),
        SettingRow("Allow unsafe operations", "allowUnsafeOperations", settings.allowUnsafeOperations.toString()),
        SettingRow("Default attached data lease", "defaultAttachedDataLease", "${settings.defaultAttachedDataLease}"),
        SettingRow("Max attached data lease", "maxAttachedDataLease", settings.maxAttachedDataLease.toString()),
        SettingRow(
            "Content type detector",
            "contentTypeDetector",
            settings.contentTypeDetector::class.simpleName ?: "?",
        ),
        SettingRow(
            "Model cache: max resident models",
            "modelCache.maxResidentModels",
            settings.modelCache.maxResidentModels.toString(),
        ),
        SettingRow("Jobs: on unloadable job", "jobs.onUnloadableJob", jobs.onUnloadableJob.toString()),
        SettingRow("Jobs: execution", "jobs.execution", jobs.execution.toString()),
        SettingRow("Jobs: succeeded retention", "jobs.succeededRetention", jobs.succeededRetention.toString()),
        SettingRow("Jobs: cancelled retention", "jobs.cancelledRetention", jobs.cancelledRetention.toString()),
        SettingRow("Jobs: dead letter retention", "jobs.deadLetterRetention", jobs.deadLetterRetention.toString()),
        SettingRow("Jobs: hard queue limit", "jobs.hardQueueLimit", jobs.hardQueueLimit.toString()),
        SettingRow("Jobs: max parallel steps", "jobs.maxParallelSteps", jobs.maxParallelSteps.toString()),
        SettingRow("Jobs: poll interval", "jobs.pollInterval", jobs.pollInterval.toString()),
        SettingRow("Jobs: backoff base", "jobs.backoffBase", jobs.backoffBase.toString()),
    )

    support.respondPage(call, "Settings") {
        header {
            nav { div { a(href = support.pathProvider.withPrefix()) { +"Home" } } }
        }
        main {
            h1 { +"Settings" }

            table {
                thead {
                    tr {
                        th { +"Setting" }
                        th { +"Value" }
                    }
                }
                tbody {
                    for (row in rows) {
                        tr {
                            td { +row.label }
                            td { +row.value }
                        }
                    }
                }
            }
        }
    }
}

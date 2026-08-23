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
    klerk: Klerk<C, V>
) {
    val settings = klerk.settings

    val rows = listOf(
        SettingRow("Persistence", "persistence", settings.persistence::class.simpleName ?: "?"),
        SettingRow("Attached blob store", "attachedBlobStore", settings.attachedBlobStore?.let { it::class.simpleName } ?: "none"),
        SettingRow("Clock", "clock", settings.clock::class.simpleName ?: "?"),
        SettingRow("Allow unsafe operations", "allowUnsafeOperations", settings.allowUnsafeOperations.toString()),
        SettingRow("Unclaimed attached data lifetime", "unclaimedAttachedDataLifetime", settings.unclaimedAttachedDataLifetime.toString()),
        SettingRow("Max attached data lease", "maxAttachedDataLease", settings.maxAttachedDataLease.toString()),
        SettingRow("Content type detector", "contentTypeDetector", settings.contentTypeDetector::class.simpleName ?: "?"),
        SettingRow("Model cache: max resident models", "modelCache.maxResidentModels", settings.modelCache.maxResidentModels.toString()),
        SettingRow("Jobs: on unloadable job", "jobs.onUnloadableJob", settings.jobs.onUnloadableJob.toString()),
        SettingRow("Jobs: execution", "jobs.execution", settings.jobs.execution.toString()),
        SettingRow("Jobs: succeeded retention", "jobs.succeededRetention", settings.jobs.succeededRetention.toString()),
        SettingRow("Jobs: cancelled retention", "jobs.cancelledRetention", settings.jobs.cancelledRetention.toString()),
        SettingRow("Jobs: dead letter retention", "jobs.deadLetterRetention", settings.jobs.deadLetterRetention.toString()),
        SettingRow("Jobs: hard queue limit", "jobs.hardQueueLimit", settings.jobs.hardQueueLimit.toString()),
        SettingRow("Jobs: max parallel steps", "jobs.maxParallelSteps", settings.jobs.maxParallelSteps.toString()),
        SettingRow("Jobs: poll interval", "jobs.pollInterval", settings.jobs.pollInterval.toString()),
        SettingRow("Jobs: backoff base", "jobs.backoffBase", settings.jobs.backoffBase.toString()),
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
                    rows.forEach { row ->
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

package dev.klerkframework.web

import dev.klerkframework.klerk.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true }

internal suspend fun <C : KlerkContext, V> renderEventLog(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)

    val forModel = call.request.queryParameters["model"]
    val id = forModel?.let { ModelID<Any>(it.toInt()) }
    // One block, so the summary and the log describe the same moment. The entries themselves are read afterwards,
    // since the event log must not be queried while the read lock is held.
    val (modelSummary, query) = klerk.read(context) {
        (if (id == null) "" else get(id).toString()) to eventLog(id)
    }
    val events = query.get()

    support.respondPage(call, "Event log") {
            header {
                nav { div { a(href = support.pathProvider.withPrefix()) { +"Home" } } }
            }
            h1 { +"Events" }
            if (forModel != null) {
                +modelSummary
                br
                +"ID: $forModel"
            }
            table {
                thead {
                    tr {
                        th { +"Time" }
                        th { +"Actor" }
                        th { +"Event" }
                        if (forModel == null) th { +"Model" }
                    }
                }
                tbody {
                    events.forEach { event ->
                        tr {
                            td { +dateTimeFormatter.format(event.time.toLocalDateTime(TimeZone.currentSystemDefault())) }
                            td { +describeActor(event.actorType, event.actorReference, event.actorExternalId) }
                            td { a(href = "_eventlog/${event.sequenceNumber}") { +event.eventReference.eventName } }
                            if (forModel == null) td { +event.model.toString() }
                        }
                    }
                }
            }
    }
}

internal suspend fun <C : KlerkContext, V> renderEventLogDetails(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)
    val sequenceNumber = requireNotNull(call.parameters["id"]).toLong()
    val event = klerk.read(context) { eventLog(sequenceNumber = sequenceNumber) }.get().single()

    support.respondPage(call, "Event log entry") {
            header {
                nav {
                    div {
                        a(href = support.pathProvider.withPrefix()) { +"Home" }
                        +" / "
                        a(href = "${support.pathProvider.withPrefix()}_eventlog") { +"Event log" }
                    }
                }
            }
            h1 { +"Event details" }
            table {
                tr {
                    td { +"Time" }
                    td { +dateTimeFormatter.format(event.time.toLocalDateTime(TimeZone.currentSystemDefault())) }
                }
                tr {
                    td { +"Actor" }
                    td { +describeActor(event.actorType, event.actorReference, event.actorExternalId) }
                }
                tr {
                    td { +"Event" }
                    td { +event.eventReference.eventName }
                }
                tr {
                    td { +"Model ID" }
                    td { +"${event.model} (${event.model.value})" }
                }
            }
            h2 { +"Parameters" }
            val jsonPretty = prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(event.params))
            textArea {
                disabled = true
                rows = jsonPretty.lines().size.toString()
                +jsonPretty
            }
    }
}

internal fun describeActor(actorType: ActorType, actorReference: Int?, actorExternalId: Long?): String {
    val type = actorType.name
    val modelReference = if (actorReference == null) "" else "Model reference: $actorReference"
    val externalId = if (actorExternalId == null) "" else "External ID: $actorExternalId"
    return "$type $modelReference $externalId"
}

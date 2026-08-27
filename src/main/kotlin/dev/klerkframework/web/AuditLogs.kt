package dev.klerkframework.web

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.klerkframework.klerk.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderAudit(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)

    val forModel = call.request.queryParameters["model"]
    val id = forModel?.let { ModelID<Any>(it.toInt()) }
    // One block, so the summary and the log describe the same moment. The entries themselves are read afterwards,
    // since the audit log must not be queried while the read lock is held.
    val (modelSummary, query) = klerk.read(context) {
        (if (id == null) "" else get(id).toString()) to auditLog(id)
    }
    val events = query.get()

    support.respondPage(call, "Audit log") {
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
                            td { a(href = "_audit/${event.sequenceNumber}") { +event.eventReference.eventName } }
                            if (forModel == null) td { +(ModelID<Any>(event.reference).toString()) }
                        }
                    }
                }
            }
    }
}

internal suspend fun <C : KlerkContext, V> renderAuditDetails(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)
    val sequenceNumber = requireNotNull(call.parameters["id"]).toLong()
    val event = klerk.read(context) { auditLog(sequenceNumber = sequenceNumber) }.get().single()

    support.respondPage(call, "Audit entry") {
            header {
                nav {
                    div {
                        a(href = support.pathProvider.withPrefix()) { +"Home" }
                        +" / "
                        a(href = "${support.pathProvider.withPrefix()}_audit") { +"Audit log" }
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
                    td { +"${ModelID<Any>(event.reference)} (${event.reference})" }
                }
            }
            h2 { +"Parameters" }
            val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
            val jsonPretty = gson.toJson(JsonParser.parseString(event.params))
            textArea {
                disabled = true
                rows = jsonPretty.lines().size.toString()
                +jsonPretty
            }
    }
}

internal fun describeActor(actorType: Byte, actorReference: Int?, actorExternalId: Long?): String {
    val type = when (actorType) {
        ActorIdentity.systemType.toByte() -> "system"
        ActorIdentity.authentication.toByte() -> "authentication"
        ActorIdentity.modelType.toByte() -> "model"
        ActorIdentity.modelReferenceType.toByte() -> "modelReference"
        ActorIdentity.unauthenticatedType.toByte() -> "unauthenticated"
        ActorIdentity.customType.toByte() -> "custom"
        else -> error("Unknown ActorIdentity $actorType")
    }
    val modelReference = if (actorReference == null) "" else "Model reference: $actorReference"
    val externalId = if (actorExternalId == null) "" else "External ID: $actorExternalId"
    return "$type $modelReference $externalId"
}

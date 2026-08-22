package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.job.JobId
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.job.JobLogEntry
import dev.klerkframework.klerk.job.JobStatus
import dev.klerkframework.klerk.job.JobsSpecification
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

internal suspend fun <C : KlerkContext, V> renderJobs(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)
    val statusFilter = call.request.queryParameters["status"]?.let { name ->
        JobStatus.entries.firstOrNull { it.name == name }
    }
    // Enabled by default: only an explicit "false" turns it off, so a first visit (no query params at all) has it on.
    val autoRefresh = call.request.queryParameters["autorefresh"] != "false"
    val jobs = klerk.jobs.getAllJobs(context).filter { statusFilter == null || it.status == statusFilter }

    fun jobsUrl(status: JobStatus?, autoRefreshValue: Boolean): String {
        val params = buildList {
            status?.let { add("status=${it.name}") }
            if (!autoRefreshValue) add("autorefresh=false")
        }
        return if (params.isEmpty()) jobsPath else "$jobsPath?${params.joinToString("&")}"
    }

    support.respondPage(call, "Jobs", pageHead = if (autoRefresh) autoRefresh(15) else null) {
            header {
                nav {
                    div {
                        a(href = support.pathProvider.base) { +"Home" }
                        +" / "
                        a(href = "$jobsPath/types") { +"Job types" }
                    }
                }
            }
            main {
                h1 { +"Jobs" }
                p {
                    label {
                        input(type = InputType.checkBox) {
                            checked = autoRefresh
                            onChange = "window.location = '${jobsUrl(statusFilter, !autoRefresh)}'"
                        }
                        +" Auto-refresh (15s)"
                    }
                }
                p {
                    +"Filter: "
                    a(href = jobsUrl(null, autoRefresh)) { +"All" }
                    JobStatus.entries.forEach { status ->
                        +" | "
                        a(href = jobsUrl(status, autoRefresh)) { +status.name }
                    }
                }
                table {
                    thead {
                        tr {
                            th { +"Id" }
                            th { +"Name" }
                            th { +"Status" }
                            th { +"Priority" }
                            th { +"Parent" }
                            th { +"Progress" }
                            th { +"Created" }
                        }
                    }
                    tbody {
                        jobs.forEach { job ->
                            tr {
                                onClick = """window.location = '$jobsPath/${job.id}';"""
                                td { +job.id.toString() }
                                td { +job.name.value }
                                td { apply(jobStatusBadge(job.status)) }
                                td { +job.priority.name }
                                td {
                                    job.parent?.let { parentId ->
                                        a(href = "$jobsPath/$parentId") { +parentId.toString() }
                                    }
                                }
                                td { apply(jobProgressBar(job)) }
                                td { +dateTimeFormatter.format(job.created.toLocalDateTime(TimeZone.currentSystemDefault())) }
                            }
                        }
                    }
                }

                apply(cronSchedulesTable(klerk.specification.jobs))
            }
    }
}

internal suspend fun <C : KlerkContext, V> renderJobDetails(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>
) {
    val context = support.contextProvider(call, klerk)
    val id = JobId(requireNotNull(call.parameters["id"]).toInt())
    val job = klerk.jobs.getJob(id, context)
    val csrfToken = Csrf.issue(call)

    support.respondPage(
        call,
        "Job ${job.id}",
        pageHead = if (!job.status.isTerminal) autoRefresh(3) else null,
    ) {
            nav {
                div {
                    a(href = support.pathProvider.withPrefix()) { +"Home" }
                    +" / "
                    a(href = jobsPath) { +"Jobs" }
                }
            }
            h1 { +"Job details" }
            table {
                tbody {
                    tr { td { +"Id" }; td { +job.id.toString() } }
                    tr { td { +"Name" }; td { +job.name.value } }
                    tr { td { +"Status" }; td { apply(jobStatusBadge(job.status)) } }
                    tr { td { +"Priority" }; td { +job.priority.name } }
                    tr { td { +"Step / attempt" }; td { +"${job.step} / ${job.attempt}" } }
                    tr { td { +"Created" }; td { +dateTimeFormatter.format(job.created.toLocalDateTime(TimeZone.currentSystemDefault())) } }
                    job.parent?.let { parentId ->
                        tr { td { +"Parent" }; td { a(href = "$jobsPath/$parentId") { +parentId.toString() } } }
                    }
                    if (job.depth > 0) {
                        tr { td { +"Root" }; td { a(href = "$jobsPath/${job.root}") { +job.root.toString() } } }
                        tr { td { +"Depth" }; td { +job.depth.toString() } }
                    }
                    job.progress?.let { progress ->
                        tr {
                            td { +"Progress" }
                            td {
                                if (progress.total != null) {
                                    progress {
                                        attributes["value"] = progress.completed.toString()
                                        attributes["max"] = progress.total.toString()
                                    }
                                    +" "
                                }
                                +formatProgress(job)
                            }
                        }
                    }
                    if (job.cancellationRequested) {
                        tr { td { +"Cancellation requested" }; td { +"Yes" } }
                    }
                    job.hook?.let { hook ->
                        tr { td { +"Unwinding through" }; td { +hook.name } }
                    }
                    job.reason?.let { reason ->
                        tr { td { +"Reason" }; td { +reason } }
                    }
                    job.ownerActorId?.let { ownerId ->
                        tr { td { +"Owner" }; td { +ownerId.toString() } }
                    }
                }
            }

            div {
                if (!job.status.isTerminal) {
                    form(action = "$jobsPath/${job.id}/cancel", method = FormMethod.post) {
                        with(Csrf) { tokenInput(csrfToken) }
                        button { +"Cancel" }
                    }
                }
                if (job.status == JobStatus.DeadLettered) {
                    form(action = "$jobsPath/${job.id}/resume", method = FormMethod.post) {
                        with(Csrf) { tokenInput(csrfToken) }
                        button { +"Resume" }
                    }
                }
                if (job.status.isTerminal) {
                    form(action = "$jobsPath/${job.id}/delete", method = FormMethod.post) {
                        with(Csrf) { tokenInput(csrfToken) }
                        button { +"Delete" }
                    }
                }
            }

            div {
                h3 { +"Log" }
                table {
                    thead {
                        tr {
                            th { +"Time" }
                            th { +"Level" }
                            th { +"Message" }
                        }
                    }
                    tbody {
                        job.log.forEach { entry -> apply(jobLogRow(entry)) }
                    }
                }
            }
    }
}

internal suspend fun <C : KlerkContext, V> renderJobTypes(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>
) {
    val jobsConfig = klerk.specification.jobs

    support.respondPage(call, "Job types") {
            nav {
                div {
                    a(href = support.pathProvider.withPrefix()) { +"Home" }
                    +" / "
                    a(href = jobsPath) { +"Jobs" }
                }
            }
            h1 { +"Job types" }
            table {
                thead {
                    tr {
                        th { +"Name" }
                        th { +"Agent" }
                        th { +"Priority" }
                        th { +"Max retries" }
                        th { +"Max concurrent" }
                        th { +"Max steps" }
                        th { +"Max duration" }
                        th { +"Max descendants" }
                        th { +"Max depth" }
                    }
                }
                tbody {
                    jobsConfig.types.values.sortedBy { it.name.value }.forEach { type ->
                        tr {
                            td { +type.name.value }
                            td { +type.agent.name }
                            td { +(type.priority?.name ?: "(inherited)") }
                            td { +type.maxRetries.toString() }
                            td { +(type.maxConcurrent?.toString() ?: "(unlimited)") }
                            td { +(type.maxSteps?.toString() ?: "(unlimited)") }
                            td { +(type.maxDuration?.toString() ?: "(unlimited)") }
                            td { +type.maxDescendants.toString() }
                            td { +type.maxDepth.toString() }
                        }
                    }
                }
            }

            apply(cronSchedulesTable(jobsConfig))
    }
}

internal suspend fun <C : KlerkContext, V> handleJobCancel(call: ApplicationCall, support: WebSupport<C, V>, jobsPath: String, klerk: Klerk<C, V>) {
    Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val id = JobId(requireNotNull(call.parameters["id"]).toInt())
    klerk.jobs.cancel(id, context)
    call.respondRedirect("$jobsPath/$id")
}

internal suspend fun <C : KlerkContext, V> handleJobResume(call: ApplicationCall, support: WebSupport<C, V>, jobsPath: String, klerk: Klerk<C, V>) {
    Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val id = JobId(requireNotNull(call.parameters["id"]).toInt())
    klerk.jobs.resume(id, context)
    call.respondRedirect("$jobsPath/$id")
}

internal suspend fun <C : KlerkContext, V> handleJobDelete(call: ApplicationCall, support: WebSupport<C, V>, jobsPath: String, klerk: Klerk<C, V>) {
    Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val id = JobId(requireNotNull(call.parameters["id"]).toInt())
    klerk.jobs.delete(id, context)
    call.respondRedirect(jobsPath)
}

private fun <C : KlerkContext, V> cronSchedulesTable(jobsConfig: JobsSpecification<C, V>): FlowContent.() -> Unit = {
    h2 { +"Cron jobs" }
    if (jobsConfig.crons.isEmpty()) {
        p { +"No cron jobs configured" }
    } else {
        table {
            thead {
                tr {
                    th { +"Job" }
                    th { +"Expression" }
                    th { +"Catch-up" }
                    th { +"Overlap" }
                    th { +"Jitter" }
                }
            }
            tbody {
                jobsConfig.crons.forEach { cron ->
                    tr {
                        td { +cron.type.name.value }
                        td { +cron.expression }
                        td { +cron.catchUp.name }
                        td { +cron.overlap.name }
                        td { +cron.jitter.toString() }
                    }
                }
            }
        }
    }
}

private fun jobProgressBar(job: JobInfo): TD.() -> Unit = {
    job.progress?.let { progress ->
        progress(classes = "job-progress") {
            style = "width: 60px;"
            attributes["value"] = progress.completed.toString()
            if (progress.total != null) {
                attributes["max"] = progress.total.toString()
            }
        }
    }
}

private fun formatProgress(job: JobInfo): String {
    val progress = job.progress ?: return ""
    val base = if (progress.total != null) "${progress.completed}/${progress.total}" else "${progress.completed}"
    return if (progress.message != null) "$base ${progress.message}" else base
}

private fun jobStatusBadge(status: JobStatus): TD.() -> Unit = {
    span(classes = "job-status status-${status.name.lowercase()}") { +status.name }
}

private fun jobLogRow(entry: JobLogEntry): TBODY.() -> Unit = {
    tr {
        td { +dateTimeFormatter.format(entry.time.toLocalDateTime(TimeZone.currentSystemDefault())) }
        td { span(classes = "log-level log-level-${entry.level.name.lowercase()}") { +entry.level.name } }
        td { +entry.message }
    }
}

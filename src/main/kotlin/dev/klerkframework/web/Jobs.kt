package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.job.JobInfo
import dev.klerkframework.klerk.job.JobLogEntry
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobOperation
import dev.klerkframework.klerk.job.JobPriority
import dev.klerkframework.klerk.JobRejectedException
import dev.klerkframework.klerk.job.JobStatus
import dev.klerkframework.klerk.job.JobsSpecification
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*

private fun <C : KlerkContext, V> jobColumns(jobs: JobsSpecification<C, V>): List<ColumnSpec<JobInfo>> {
    fun column(
        header: String,
        key: String,
        kind: ColumnKind?,
        nullable: Boolean = false,
        value: ((JobInfo) -> Any?)?,
    ) = ColumnSpec(header, key, kind, nullable, value, kind?.sortable == true, kind?.filterable == true)

    return listOf(
        column("Id", "id", ColumnKind.Numeric(String::toLongOrNull)) { it.id.value },
        column("Name", "name", ColumnKind.Text) { it.name.value },
        column("Registered by", "registered-by", ColumnKind.Text) { registeredByLabel(jobs, it.name) },
        column("Status", "status", ColumnKind.Choice(JobStatus.entries.map { it.name })) { it.status },
        column("Priority", "priority", ColumnKind.Choice(JobPriority.entries.map { it.name })) { it.priority },
        column("Parent", "parent", ColumnKind.Numeric(String::toLongOrNull), nullable = true) { it.parent?.value },
        ColumnSpec("Progress", null, null, false, null, isSortable = false, isFilterable = false),
        column("Created", "created", ColumnKind.InstantKind) { it.createdAt },
    )
}

internal suspend fun <C : KlerkContext, V> renderJobs(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    val context = support.contextProvider(call, klerk)
    // Enabled by default: only an explicit "false" turns it off, so a first visit (no query params at all) has it on.
    val autoRefresh = call.request.queryParameters["autorefresh"] != "false"
    val columns = jobColumns(klerk.specification.jobs)
    val names = TableParameterNames("")
    val request = parseTableRequest(call.request.queryParameters, columns, names, defaultSort = null)
    if (request.problems.isNotEmpty()) {
        call.response.status(HttpStatusCode.BadRequest)
    }
    val filters = request.filters
    val jobs = if (request.problems.isEmpty()) {
        klerk.jobs.all(context)
            .filter { job -> filters.all { it.matches(job) } }
            .let { all -> request.sort?.let { all.sortedWith(it.comparator()) } ?: all }
    } else {
        emptyList()
    }

    val runnableCrons = runnableCrons(klerk, context)
    val csrfToken = Csrf.issue(call)

    support.respondPage(call, "Jobs", status = null, pageHead = if (autoRefresh) autoRefresh(15) else null) {
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
                        val toggled = withQueryParam(call.request.uri, "autorefresh", (!autoRefresh).toString())
                        onChange = "window.location = '$toggled'"
                    }
                    +" Auto-refresh (15s)"
                }
            }
            if (request.problems.isNotEmpty()) {
                apply(renderProblems(request.problems, call.request.uri))
                return@main
            }
            apply(renderFilter(call, emptyList(), columns, request, names, shownView = null))
            apply(renderActiveFilters(call, request, names))
            table {
                thead {
                    tr {
                        for (column in columns) {
                            th { headerCell(column, request, names, call, TableControls.Panel) }
                        }
                    }
                }
                tbody {
                    for (job in jobs) {
                        tr {
                            onClick = """window.location = '$jobsPath/${job.id}';"""
                            td { +job.id.toString() }
                            td { +job.name.value }
                            td { +registeredByLabel(klerk.specification.jobs, job.name) }
                            td { apply(jobStatusBadge(job.status)) }
                            td { +job.priority.name }
                            td {
                                job.parent?.let { parentId ->
                                    a(href = "$jobsPath/$parentId") { +parentId.toString() }
                                }
                            }
                            td { apply(jobProgressBar(job)) }
                            td { +formatTime(job.createdAt) }
                        }
                    }
                }
            }

            apply(cronSchedulesTable(klerk.specification.jobs, runnableCrons, jobsPath, csrfToken))
        }
    }
}

internal suspend fun <C : KlerkContext, V> renderJobDetails(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    val context = support.contextProvider(call, klerk)
    val id = JobID(requireNotNull(call.parameters["id"]).toLong())
    val job = klerk.jobs.get(id, context)
    val allowed = JobOperation.entries.filter { klerk.jobs.isAllowed(id, it, context) }.toSet()
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
                tr {
                    td { +"Id" }
                    td { +job.id.toString() }
                }
                tr {
                    td { +"Name" }
                    td { +job.name.value }
                }
                tr {
                    td { +"Registered by" }
                    td { +registeredByLabel(klerk.specification.jobs, job.name) }
                }
                tr {
                    td { +"Status" }
                    td { apply(jobStatusBadge(job.status)) }
                }
                tr {
                    td { +"Priority" }
                    td { +job.priority.name }
                }
                tr {
                    td { +"Step / attempt" }
                    td { +"${job.step} / ${job.attempt}" }
                }
                tr {
                    td { +"Created" }
                    td { +formatTime(job.createdAt) }
                }
                job.parent?.let { parentId ->
                    tr {
                        td { +"Parent" }
                        td { a(href = "$jobsPath/$parentId") { +parentId.toString() } }
                    }
                }
                if (job.depth > 0) {
                    tr {
                        td { +"Root" }
                        td { a(href = "$jobsPath/${job.root}") { +job.root.toString() } }
                    }
                    tr {
                        td { +"Depth" }
                        td { +job.depth.toString() }
                    }
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
                    tr {
                        td { +"Cancellation requested" }
                        td { +"Yes" }
                    }
                }
                job.hook?.let { hook ->
                    tr {
                        td { +"Unwinding through" }
                        td { +hook.name }
                    }
                }
                job.reason?.let { reason ->
                    tr {
                        td { +"Reason" }
                        td { +reason }
                    }
                }
                tr {
                    td { +"Owner" }
                    td { +job.owner.toString() }
                }
            }
        }

        div {
            if (!job.status.isTerminal && JobOperation.Cancel in allowed) {
                form(action = "$jobsPath/${job.id}/cancel", method = FormMethod.post) {
                    with(Csrf) { tokenInput(csrfToken) }
                    button { +"Cancel" }
                }
            }
            if (job.status == JobStatus.DeadLettered && JobOperation.Resume in allowed) {
                form(action = "$jobsPath/${job.id}/resume", method = FormMethod.post) {
                    with(Csrf) { tokenInput(csrfToken) }
                    button { +"Resume" }
                }
            }
            if (job.status.isTerminal && JobOperation.Delete in allowed) {
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
                    for (entry in job.log) {
                        apply(jobLogRow(entry))
                    }
                }
            }
        }
    }
}

internal suspend fun <C : KlerkContext, V> renderJobTypes(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    val jobsConfig = klerk.specification.jobs
    val runnableCrons = runnableCrons(klerk, support.contextProvider(call, klerk))
    val csrfToken = Csrf.issue(call)

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
                    th { +"Registered by" }
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
                        td { +registeredByLabel(jobsConfig, type.name) }
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

        apply(cronSchedulesTable(jobsConfig, runnableCrons, jobsPath, csrfToken))
    }
}

internal suspend fun <C : KlerkContext, V> handleJobCancel(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val id = JobID(requireNotNull(call.parameters["id"]).toLong())
    klerk.jobs.cancel(id, context)
    call.respondRedirect("$jobsPath/$id")
}

internal suspend fun <C : KlerkContext, V> handleJobResume(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val id = JobID(requireNotNull(call.parameters["id"]).toLong())
    klerk.jobs.resume(id, context)
    call.respondRedirect("$jobsPath/$id")
}

internal suspend fun <C : KlerkContext, V> handleJobDelete(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val id = JobID(requireNotNull(call.parameters["id"]).toLong())
    klerk.jobs.delete(id, context)
    call.respondRedirect(jobsPath)
}

internal suspend fun <C : KlerkContext, V> handleCronRun(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    jobsPath: String,
    klerk: Klerk<C, V>,
) {
    val parameters = Csrf.receiveVerifiedParameters(call) ?: return
    val context = support.contextProvider(call, klerk)
    val schedule = klerk.specification.jobs.crons.firstOrNull { it.id == parameters["schedule"] }
        ?: return call.respond(HttpStatusCode.NotFound, "There is no such cron schedule")
    val id = try {
        klerk.jobs.runNow(schedule, context)
    } catch (e: IllegalStateException) {
        return call.respond(HttpStatusCode.Conflict, e.message ?: "Could not run the schedule")
    } catch (e: JobRejectedException) {
        return call.respond(HttpStatusCode.ServiceUnavailable, e.message ?: "The job was refused")
    }
    call.respondRedirect("$jobsPath/$id")
}

private fun <C : KlerkContext, V> registeredByLabel(jobs: JobsSpecification<C, V>, name: JobName): String =
    jobs.registeredBy(name)?.let { "plugin $it" } ?: "application"

private suspend fun <C : KlerkContext, V> runnableCrons(klerk: Klerk<C, V>, context: C): Set<String> =
    klerk.specification.jobs.crons.filter { klerk.jobs.canRunNow(it, context) }.map { it.id }.toSet()

private fun <C : KlerkContext, V> cronSchedulesTable(
    jobsConfig: JobsSpecification<C, V>,
    runnable: Set<String>,
    jobsPath: String,
    csrfToken: String,
): FlowContent.() -> Unit = {
    h2 { +"Cron jobs" }
    if (jobsConfig.crons.isEmpty()) {
        p { +"No cron jobs configured" }
    } else {
        table {
            thead {
                tr {
                    th { +"Job" }
                    th { +"Registered by" }
                    th { +"Expression" }
                    th { +"Catch-up" }
                    th { +"Overlap" }
                    th { +"Jitter" }
                    th {}
                }
            }
            tbody {
                for (cron in jobsConfig.crons) {
                    tr {
                        td { +cron.type.name.value }
                        td { +registeredByLabel(jobsConfig, cron.type.name) }
                        td { +cron.expression }
                        td { +cron.catchUp.name }
                        td { +cron.overlap.name }
                        td { +cron.jitter.toString() }
                        td {
                            if (cron.id in runnable) {
                                form(action = "$jobsPath/crons/run", method = FormMethod.post) {
                                    with(Csrf) { tokenInput(csrfToken) }
                                    hiddenInput(name = "schedule") { value = cron.id }
                                    button {
                                        onClick = "return confirm('Run ${cron.type.name.value} now?');"
                                        +"Run now"
                                    }
                                }
                            }
                        }
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

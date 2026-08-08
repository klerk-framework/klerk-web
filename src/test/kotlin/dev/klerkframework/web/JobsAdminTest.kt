package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.job.JobExecution
import dev.klerkframework.klerk.job.JobStatus
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun ApplicationCall.systemCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

class JobsAdminTest {

    private fun setup(): Pair<Klerk<Context, MyCollections>, KlerkWeb<Context, MyCollections>> {
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val klerk = Klerk.create(createConfig(collections, jobExecution = JobExecution.Manual))
        val klerkWeb = KlerkWeb(klerk, ApplicationCall::systemCtx)
        return Pair(klerk, klerkWeb)
    }

    @Test
    fun `job list shows a scheduled job and status filter narrows results`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()
        val jobId = klerk.jobs.schedule(MyJob.schedule(""), Context.system())

        application { routing { apply(klerkWeb.generateRoutes()) } }

        val listBody = client.get("/admin/_jobs").bodyAsText()
        assertTrue(listBody.contains(jobId.toString()))
        assertTrue(listBody.contains("my-job"))

        val filtered = client.get("/admin/_jobs?status=${JobStatus.Ready.name}").bodyAsText()
        assertTrue(filtered.contains(jobId.toString()))

        val filteredOut = client.get("/admin/_jobs?status=${JobStatus.Succeeded.name}").bodyAsText()
        assertFalse(filteredOut.contains(jobId.toString()))
    }

    @Test
    fun `job detail renders fields and log, and can be cancelled`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()
        val jobId = klerk.jobs.schedule(MyJob.schedule(""), Context.system())

        application { routing { apply(klerkWeb.generateRoutes()) } }

        val detail = client.get("/admin/_jobs/$jobId").bodyAsText()
        assertTrue(detail.contains("my-job"))
        assertTrue(detail.contains("Cancel"))

        val cancelResponse = client.post("/admin/_jobs/$jobId/cancel")
        assertTrue(cancelResponse.status.value in 200..399)

        klerk.jobs.runUntilIdle()
        val job = klerk.jobs.getJob(jobId, Context.system())
        assertTrue(job.status == JobStatus.Cancelled || job.status == JobStatus.Cancelling)
    }

    @Test
    fun `terminal job can be deleted`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()
        val jobId = klerk.jobs.schedule(MyJob.schedule(""), Context.system())
        klerk.jobs.runUntilIdle()
        assertTrue(klerk.jobs.getJob(jobId, Context.system()).status == JobStatus.Succeeded)

        application { routing { apply(klerkWeb.generateRoutes()) } }

        val deleteResponse = client.post("/admin/_jobs/$jobId/delete")
        assertTrue(deleteResponse.status.value in 200..399)

        val remaining = klerk.jobs.getAllJobs(Context.system())
        assertTrue(remaining.none { it.id == jobId })
    }

    @Test
    fun `job types page lists registered type and cron schedule`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()

        application { routing { apply(klerkWeb.generateRoutes()) } }

        val body = client.get("/admin/_jobs/types").bodyAsText()
        assertTrue(body.contains("my-job"))
        assertTrue(body.contains("0 3 * * *"))
    }
}

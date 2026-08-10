package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ManagedModel
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.job.JobName
import dev.klerkframework.klerk.job.JobProgress
import dev.klerkframework.klerk.job.JobResult
import dev.klerkframework.klerk.job.JobStepArgs
import dev.klerkframework.klerk.job.JobType
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.read.ModelModification.*
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.web.assets.CssAsset
import dev.klerkframework.web.assets.JsAsset
import dev.klerkframework.web.config.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import org.sqlite.SQLiteDataSource
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Encodes a [PeriodicPingJob] yield-in-progress in the (plain-`String`) cursor, since this module has no
 * kotlinx.serialization compiler plugin to derive a serializer for a proper data class cursor.
 */
private data class YieldProgress(val total: Int, val completed: Int) {
    fun encode(): String = "yield:$total:$completed"

    companion object {
        fun parse(cursor: String): YieldProgress? {
            if (!cursor.startsWith("yield:")) return null
            val (total, completed) = cursor.removePrefix("yield:").split(":").map { it.toInt() }
            return YieldProgress(total, completed)
        }
    }
}

/**
 * Fires every 15 seconds, simulating 5 seconds of work each time, and exercises every kind of [JobResult] along the
 * way — including an occasional uncaught exception (which klerk treats as [JobResult.Fail]).
 *
 * klerk's `cron()` scheduling is minute-granularity, so this instead reschedules itself. Step 0 immediately spawns
 * the next generation — timed 15 seconds from `context.time` *now*, not `job.created` (which is when this instance
 * was spawned, i.e. when the *previous* generation finished, which would make each generation fire 5 seconds early)
 * — so the periodic cadence continues no matter what this generation's random outcome below turns out to be.
 *
 * Every step after that does the simulated work, then:
 * - if it has already started yielding (tracked via [YieldProgress] in the cursor), it just reports progress and
 *   moves closer to [JobResult.Success] — no more random failures once it has committed to that path.
 * - otherwise it rolls a random [JobResult]: [JobResult.Success] ends it cleanly; [JobResult.Fail] retries with
 *   backoff and eventually dead-letters; [JobResult.Abort] dead-letters immediately; [JobResult.Yield] picks a
 *   random step count (1..15) to report progress over before succeeding; and occasionally the step just throws.
 *
 * Since each generation is a child of the previous one, maxDepth/maxDescendants are raised well past what this demo
 * server will ever run long enough to reach.
 */
object PeriodicPingJob : JobType.Local<String, Context, MyCollections>() {
    override val name: JobName = JobName("periodic-ping")
    override val maxDepth: Int = Int.MAX_VALUE
    override val maxDescendants: Int = Int.MAX_VALUE

    override suspend fun step(args: JobStepArgs.Local<String, Context, MyCollections>): JobResult<String> {
        if (args.job.step == 0 && args.job.attempt == 0) {
            val nextRun = args.context.time + 15.seconds
            return JobResult.Yield(
                cursor = args.cursor,
                spawn = listOf(PeriodicPingJob.schedule(args.cursor, scheduleAt = nextRun)),
                log = listOf(args.info("Scheduled the next run for $nextRun")),
            )
        }

        val log = mutableListOf(args.info("Working..."))
        delay(5.seconds)
        log += args.info("Done")

        val inProgress = YieldProgress.parse(args.cursor)
        if (inProgress != null) {
            val completed = inProgress.completed + 1
            if (completed >= inProgress.total) {
                log += args.info("Reached step $completed/${inProgress.total}, succeeding")
                return JobResult.Success(log = log)
            }
            log += args.info("Progress $completed/${inProgress.total}")
            return JobResult.Yield(
                cursor = YieldProgress(inProgress.total, completed).encode(),
                progress = JobProgress(completed = completed, total = inProgress.total),
                log = log,
            )
        }

        return when (Random.nextInt(5)) {
            0 -> {
                log += args.info("Succeeding")
                JobResult.Success(log = log)
            }

            1 -> {
                log += args.warn("Failing (will retry with backoff)")
                JobResult.Fail("Simulated transient failure", log = log)
            }

            2 -> {
                log += args.error("Aborting (straight to dead letter)")
                JobResult.Abort("Simulated permanent failure", log = log)
            }

            3 -> {
                val total = Random.nextInt(1, 16)
                log += args.info("Yielding for $total steps")
                val progress = YieldProgress(total, 1)
                JobResult.Yield(
                    cursor = progress.encode(),
                    progress = JobProgress(completed = progress.completed, total = progress.total),
                    log = log,
                )
            }

            else -> {
                // An uncaught exception never reaches a returned JobResult, so it can't carry a log entry — this is
                // the one outcome that still has to go to stdout instead of the job's own log.
                println("PeriodicPingJob: throwing an uncaught exception")
                throw RuntimeException("Simulated uncaught exception from PeriodicPingJob")
            }
        }
    }
}

fun main() {
    System.setProperty("DEVELOPMENT_MODE", "true")
    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())

    val dbFilePath = "/tmp/klerktest.sqlite"
    //File(dbFilePath).delete()
    val ds = SQLiteDataSource()
    ds.url = "jdbc:sqlite:$dbFilePath"
    //val persistence = SqlPersistence(ds)
    val persistence = RamStorage()
    val klerk = Klerk.create(createConfig(collections, persistence, extraJobs = { register(PeriodicPingJob) }))
    runBlocking {

        klerk.meta.start()
        klerk.jobs.schedule(PeriodicPingJob.schedule(""), Context.system())

        if (klerk.meta.modelsCount < 10) {
            //   val rowling = createAuthorJKRowling(klerk)
            //    val book = createBookHarryPotter1(klerk, rowling)

            generateSampleData(5, 2, klerk)
            //data.makeSnapshot()
        }

        val embeddedServer = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            configureRouting(klerk)
            //        configureSecurity()
            //      configureHTTP()
            install(Compression)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down")
            embeddedServer.stop()
            klerk.meta.stop()
            println("Shutdown complete")
        })

        embeddedServer.start(wait = false)

        klerk.models.subscribe(Context.system(), null).collect {
            when (it) {
                is Created -> println("${it.id} was created")
                is PropsUpdated -> println("${it.id} had props updated")
                is Transitioned -> println("${it.id} transitioned")
                is Deleted -> println("${it.id} was deleted")
            }
        }

    }

}

suspend fun ApplicationCall.ctx(klerk: Klerk<Context, MyCollections>): Context = Context.swedishUnauthenticated()

suspend fun canSeeAdminUI(context: Context): Boolean {
    return true
}

val css = CssAsset("water.css")

val pathProvider = DefaultPathProvider()
val layout = Layout(css = css, assetsBase = pathProvider.assetsBase)

//val css = CssAsset("/assets/matcha.css") // CssAsset("/assets/my-styles.css")
//val css = CssAsset("assets/water.css") // CssAsset("/assets/my-styles.css")
val myScript = JsAsset("other/my-script.js")

fun Application.configureRouting(klerk: Klerk<Context, MyCollections>) {
    val klerkWeb = KlerkWeb(
        klerk,
        ApplicationCall::ctx,
        canSeeAdminUI = { true },
        pathProvider = pathProvider,
        layout = layout,
        classProvider = MyClassProvider,
        useTableForDetails = false
        )

    routing {
        apply(klerkWeb.generateRoutes())

        route(pathProvider.base) {
            get(renderIndex(klerkWeb))


            /*        get("/authors", renderAuthors(klerk))
        get("/authors/{id}", renderAuthorDetails(klerk))
        get("/books", renderBooks(klerk))
        get("/books/{id}", renderBookDetails(klerk))

 */

            get("/testassets") {
                call.respondHtml {
                    head {
                        title { +"Test assets" }
                        layout.cssUrl()?.let { styleLink(it) }
                    }
                    body {
                        h1 { +"Testing the assets. " }
                        +"Did the css and js load? Correct encoding?"
                        script(pathProvider.assetPath("other/my-script.js")) { defer = true }
                    }
                }
            }
        }

    }
}

fun HEAD.favicon(): Unit =
    link {
        rel = "icon"
        type = "image/svg+xml"
        sizes = "any"
        href =
            "data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><text y=%22.9em%22 font-size=%2290%22>\uD83E\uDDEA</text></svg>"
    }

private fun renderIndex(klerkWeb: KlerkWeb<Context, MyCollections>): suspend RoutingContext.() -> Unit = {
    call.respondHtml {
        head {
            title { +"Klerk Web Test" }
            layout.cssUrl()?.let { styleLink(it) }
            favicon()
        }
        body {
            h1 { +"Testing Klerk Web" }
            p { +"This is a example how to use klerk-web to build a web frontend." }
            h2 { +"Admin UI" }
            p {
                +"Klerk-web can generate an "
                a(href = "/admin/") { +"admin UI" }
                +" for your application."
            }
            h2 { +"Item lists" }
            apply(klerkWeb.generateNav())
        }
    }
}

private fun renderBooks(klerk: Klerk<Context, MyCollections>): suspend RoutingContext.() -> Unit = {
    val context = call.ctx(klerk)
    val support = WebSupport(klerk, ApplicationCall::ctx, pathProvider, layout, MyClassProvider)
    val table = klerk.read(context) {
        TableTemplate(klerk, Book::class, support, booksColumns).build(views.books.all, this, call)
    }
    call.respondHtml(block = layout.page("Klerk Web Test") {
        h1 { +"Here are the books" }
        apply(table.render())
    })
}



fun authorizeAllDatatypes(instance: Any) {
    TODO()
    /*instance::class.memberProperties.forEach {
        if (it.returnType.isSubtypeOf(DataContainer::class.starProjectedType)) {
            (it.getter.call(instance) as DataContainer<*>).initAuthorization(true)
        }
    }

     */
}

//class MyInstant(value: Instant) : InstantContainer(value)

object MyCssClassProvider : CssClassProvider {
    override fun classes(part: UiPart, element: String, property: String?, model: Model<*>?): Set<String> {
        return when (element) {
            "td" -> if ((model?.props as? Author)?.lastName?.value == "4") setOf("bg-accent") else setOf()
            else -> setOf()
        }
    }
}

val authorsColumns: List<Column<Author>> = listOf(
    Column(camelCaseToPretty(Author::firstName.name)) { m -> +m.props.firstName.value },
    Column(camelCaseToPretty(Author::lastName.name)) { m -> +m.props.lastName.value },
    Column("Created") { m -> +dateFormatter.format(m.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())) },
    Column("State") { m -> +m.state },
)

val booksColumns: List<Column<Book>> = listOf(
    Column(camelCaseToPretty(Book::title.name)) { m -> +m.props.title.value },
    Column("Created") { m -> +dateFormatter.format(m.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())) },
    Column("State") { m -> +m.state },
)

object MyClassProvider : CssClassProvider {
    override fun classes(part: UiPart, element: String, property: String?, model: Model<*>?): Set<String> {
        return when (element) {
            "td" -> if ((model?.props as? Author)?.lastName?.value == "4") setOf("bg-accent") else setOf()
            else -> setOf()
        }
    }
}

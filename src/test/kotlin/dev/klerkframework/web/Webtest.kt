package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.datatypes.InstantContainer
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
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import org.sqlite.SQLiteDataSource

internal var adminUI: AdminUI<Context, MyCollections>? = null
lateinit var autoButtons: AutoButtons<Context, MyCollections>

data class AuthorAndRecentPucl(val author: Author, val recentBooks: List<Book>)

fun main() {
    System.setProperty("DEVELOPMENT_MODE", "true")
    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))

    val dbFilePath = "/tmp/klerktest.sqlite"
    //File(dbFilePath).delete()
    val ds = SQLiteDataSource()
    ds.url = "jdbc:sqlite:$dbFilePath"
    //val persistence = SqlPersistence(ds)
    val persistence = RamStorage()
    val klerk = Klerk.create(createConfig(collections, persistence))
    runBlocking {

        klerk.meta.start()

        if (klerk.meta.modelsCount < 10) {
            //   val rowling = createAuthorJKRowling(klerk)
            //    val book = createBookHarryPotter1(klerk, rowling)

            generateSampleData(5, 2, klerk)
            //data.makeSnapshot()
        }

        autoButtons = AutoButtons(klerk, "_autobuttons", ApplicationCall::ctx, myStyle.getUrl())

        adminUI = AdminUI(
            klerk,
            "/admin",
            ApplicationCall::ctx,
            // cssPath = "https://unpkg.com/sakura.css/css/sakura.css",
            cssPath = myStyle.getUrl(),
            showOptionalParameters = { eventReference -> false },
            knownAlgorithms = setOf(),
            canSeeAdminUI = ::canSeeAdminUI,
            autoButtons = autoButtons
        )

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

val myStyle = CssAsset("/assets/matcha.css") // CssAsset("/assets/my-styles.css")
val myScript = JsAsset("/assets/other/my-script.js")

fun Application.configureRouting(klerk: Klerk<Context, MyCollections>) {

    routing {

        get("/", renderIndex())
        get("/authors", renderAuthors(klerk))
        get("/authors/{id}", renderAuthorDetails(klerk))

        get("/testassets") {
            call.respondHtml {
                head {
                    title { +"Test assets" }
                    styleLink(myStyle.getUrl())
                }
                body {
                    h1 { +"Testing the assets. " }
                    +"Did the css and js load? Correct encoding?"
                    script(src = myScript.getUrl()) {}

                }
            }
        }

        apply(autoButtons.registerRoutes())
        apply(adminUI!!.registerRoutes())
    }
}

private fun renderIndex(): suspend RoutingContext.() -> Unit = {
    call.respondHtml {
        head {
            title { +"Klerk Web Test" }
            styleLink(myStyle.getUrl())
        }
        body {
            h1 { +"Testing Klerk Web" }
            p { +"This is a example how to use klerk-web to build a web frontend." }
            h2 { +"Admin UI" }
            p {
                +"Klerk-web can generate an "
                a(href = "/admin") { +"admin UI" }
                +" for your application."
            }
            h2 { +"List items" }
            p {
                a(href = "/authors") { +"Go to list of authors" }
            }
            p {
                a(href = "/books") { +"Go to list of books" }
            }
        }
    }
}

private fun renderAuthors(klerk: Klerk<Context, MyCollections>): suspend RoutingContext.() -> Unit = {
    val queryResponse = klerk.read(call::ctx) {
        query(views.authors.all)
    }
    call.respondHtml {
        head {
            title { +"Klerk Web Test" }
            styleLink(myStyle.getUrl())
        }
        body {
            h1 { +"Here are the authors" }
            apply(renderTable(queryResponse, authorsTableConfig))
        }
    }

}

private fun renderAuthorDetails(klerk: Klerk<Context, MyCollections>): suspend RoutingContext.() -> Unit = {
    val id = ModelID<Author>(requireNotNull(call.parameters["id"]).toInt())
    val context = call.ctx(klerk)
    val (author, events) = klerk.read(context) {
        Pair(get(id), getPossibleEvents(id))
    }
    val completionPaths = CompletionPaths(call.request.uri, call.request.uri, call.request.uri)
    call.respondHtml {
        head {
            title { +"Klerk Web Test" }
            styleLink(myStyle.getUrl())
        }
        body {
            h1 { +"About ${author.props.firstName.value} ${author.props.lastName.value}" }
            apply(renderModel(author, includeMetadata = false))
            h2 { +"Actions" }
            p {
                events.forEach { event ->
                    apply(autoButtons.render(event, klerk, id, completionPaths, context))
                }
            }
        }
    }
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

class MyInstant(value: Instant) : InstantContainer(value)

val authorsTableConfig = TableConfig<Author>(
    caption = "Authors",
    columns = listOf(
        camelCaseToPretty(Author::firstName.name) to { m -> m.props.firstName.value },
        camelCaseToPretty(Author::lastName.name) to { m -> m.props.lastName.value },
        "Created" to { m -> dateFormatter.format(m.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())) },
        "State" to { m -> m.state },
    ),
    classProvider = object : CssClassProvider {
        override fun tableOfModels(element: String, model: Model<*>?): Set<String> {
            return when (element) {
                "td" -> if ((model?.props as? Author)?.lastName?.value == "4") setOf("bg-accent") else setOf()
                else -> setOf()
            }
        }
    },
    pathProvider = { "/authors/${it.id}" }
)

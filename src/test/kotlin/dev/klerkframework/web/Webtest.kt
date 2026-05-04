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
import dev.klerkframework.web.assets.script
import dev.klerkframework.web.assets.styleLink
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

val css = CssAsset("/assets/matcha.css") // CssAsset("/assets/my-styles.css")
val myScript = JsAsset("/assets/other/my-script.js")

fun Application.configureRouting(klerk: Klerk<Context, MyCollections>) {
    val klerkWeb = KlerkWeb(
        klerk,
        ApplicationCall::ctx,
        cssPath = css.url,
        classProvider = MyClassProvider,
        )

    routing {

        get("/", renderIndex(klerkWeb))

        apply(klerkWeb.generateRoutes())

/*        get("/authors", renderAuthors(klerk))
        get("/authors/{id}", renderAuthorDetails(klerk))
        get("/books", renderBooks(klerk))
        get("/books/{id}", renderBookDetails(klerk))

 */

        get("/testassets") {
            call.respondHtml {
                head {
                    title { +"Test assets" }
                    styleLink(css)
                }
                body {
                    h1 { +"Testing the assets. " }
                    +"Did the css and js load? Correct encoding?"
                    script(myScript) { defer = true }
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
            styleLink(css)
            favicon()
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
            h2 { +"Item lists" }
            apply(klerkWeb.generateNav())
        }
    }
}



private fun renderBooks(klerk: Klerk<Context, MyCollections>): suspend RoutingContext.() -> Unit = {
    val queryResponse = klerk.read(call::ctx) {
        query(views.books.all)
    }
    call.respondHtml {
        head {
            title { +"Klerk Web Test" }
            styleLink(css)
        }
        body {
            h1 { +"Here are the books" }
            renderTable(queryResponse, booksTableConfig)
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

object MyCssClassProvider : CssClassProvider {
    override fun tableOfModels(element: String, model: Model<*>?): Set<String> {
        return when (element) {
            "td" -> if ((model?.props as? Author)?.lastName?.value == "4") setOf("bg-accent") else setOf()
            else -> setOf()
        }
    }
}

val authorsTableConfig = TableConfig<Author>(
    caption = "Authors",
    columns = listOf(
        camelCaseToPretty(Author::firstName.name) to { m -> m.props.firstName.value },
        camelCaseToPretty(Author::lastName.name) to { m -> m.props.lastName.value },
        "Created" to { m -> dateFormatter.format(m.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())) },
        "State" to { m -> m.state },
    ),
    classProvider = MyCssClassProvider,
    pathProvider = DefaultPathProvider()
)

val booksTableConfig = TableConfig<Book>(
    caption = "Books",
    columns = listOf(
        camelCaseToPretty(Book::title.name) to { m -> m.props.title.value },
        "Created" to { m -> dateFormatter.format(m.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())) },
        "State" to { m -> m.state },
    ),
    classProvider = MyClassProvider,
    pathProvider = DefaultPathProvider()
)

object MyClassProvider : CssClassProvider {
    override fun tableOfModels(element: String, model: Model<*>?): Set<String> {
        return when (element) {
            "td" -> if ((model?.props as? Author)?.lastName?.value == "4") setOf("bg-accent") else setOf()
            else -> setOf()
        }
    }
}

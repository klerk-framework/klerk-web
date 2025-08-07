package dev.klerkframework.web

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.InstantContainer

import dev.klerkframework.klerk.read.ModelModification.*

import dev.klerkframework.klerk.storage.SqlPersistence
import dev.klerkframework.web.assets.CssAsset
import dev.klerkframework.web.assets.JsAsset
import dev.klerkframework.web.config.*

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.compression.Compression
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.html.*

import org.sqlite.SQLiteDataSource


internal var lowCodeMain: LowCodeMain<Context, MyCollections>? = null

data class AuthorAndRecentPucl(val author: Author, val recentBooks: List<Book>)

fun main() {
    System.setProperty("DEVELOPMENT_MODE", "true")
    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))

    val dbFilePath = "/tmp/klerktest.sqlite"
    //File(dbFilePath).delete()
    val ds = SQLiteDataSource()
    ds.url = "jdbc:sqlite:$dbFilePath"
    val persistence = SqlPersistence(ds)
    //val persistence = RamStorage()
    val klerk = Klerk.create(createConfig(collections, persistence))
    runBlocking {

        launch {
            println("subscribing to Klerk logs")
            klerk.log.subscribe(Context.swedishUnauthenticated()).collect { entry ->
                println("got entry: ${entry.getHeading()}")
            }
        }

        println("...")
        klerk.meta.start()

        if (klerk.meta.modelsCount == 0) {
            //val rowling = createAuthorJKRowling(klerk)
            //val book = createBookHarryPotter1(klerk, rowling)

            generateSampleData(3, 1, klerk)
            //data.makeSnapshot()
        }

        lowCodeMain = LowCodeMain(
            klerk,
            LowCodeConfig(
                "/admin",
                ::anyUser,
                // cssPath = "https://unpkg.com/sakura.css/css/sakura.css",
                cssPath = "https://unpkg.com/almond.css@latest/dist/almond.min.css",
                showOptionalParameters = ::showOptionalParameters,
                knownAlgorithms = setOf(),
                canSeeAdminUI = ::canSeeAdminUI,
            )
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

suspend fun canSeeAdminUI(context: Context): Boolean {
    return true
}

val myStyle = CssAsset("/assets/my-styles.css")
val myScript = JsAsset("/assets/other/my-script.js")

fun Application.configureRouting(klerk: Klerk<Context, MyCollections>) {

    routing {

        get("/") { call.respondRedirect("/admin") }

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

        apply(lowCodeMain!!.registerRoutes())
    }
}


fun showOptionalParameters(eventReference: EventReference): Boolean {
    return false
}

suspend fun anyUser(call: ApplicationCall): Context = Context.swedishUnauthenticated()


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

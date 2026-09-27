package dev.klerkframework.web
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.web.config.Author
import dev.klerkframework.web.config.AuthorCollections
import dev.klerkframework.web.config.BookCollections
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.MyCollections
import dev.klerkframework.web.config.createAuthorJKRowling
import dev.klerkframework.web.config.createBookHarryPotter1
import dev.klerkframework.web.config.createConfig
import dev.klerkframework.web.config.testSettings
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.html.*

fun main() {
    runBlocking {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all))
        val klerk = Klerk.create(createConfig(collections), testSettings())
        klerk.meta.start()
        if (klerk.meta.modelsCount() < 5) {
            val rowling = createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, rowling)
        }

        embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            routing {
                get("/") {
                    val table = klerk.read(Context.system()) {
                        TableTemplate<Author, Context, MyCollections>(
                            klerk,
                            Author::class,
                            WebSupport(klerk, { _, _ -> Context.system() }),
                        ).build(
                            klerk.specification.views.authors.all,
                            this,
                            call,
                        )
                    }

                    call.respondHtml {
                        head {
                            link(href = "https://unpkg.com/sakura.css/css/sakura.css", rel = "stylesheet")
                        }
                        body {
                            h1 { +"A page that shows a list" }
                            div {
                                modelTable(table)
                            }
                        }
                    }
                }
            }
        }.start(wait = true)
    }
}

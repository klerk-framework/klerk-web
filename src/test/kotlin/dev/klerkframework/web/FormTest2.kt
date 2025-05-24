package dev.klerkframework.web

import com.google.gson.Gson
import dev.klerkframework.web.config.*
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.misc.EventParameters
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import dev.klerkframework.web.ParseResult.DryRun
import dev.klerkframework.web.ParseResult.Invalid
import dev.klerkframework.web.ParseResult.Parsed
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import kotlin.reflect.full.memberProperties

fun main() {

    System.setProperty("DEVELOPMENT_MODE", "true")
    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))
    val klerk = Klerk.create(createConfig(collections))
    runBlocking {
        klerk.meta.start()
        val rowling = createAuthorJKRowling(klerk)
        createBookHarryPotter1(klerk, rowling)
    }

    val formBuilder = EventFormTemplate(
        EventWithParameters(
            CreateAuthor.id,
            EventParameters(CreateAuthorParams::class)
        ),
        klerk, "/",
    ) {
        text(CreateAuthorParams::firstName)
        text(CreateAuthorParams::lastName)
        text(CreateAuthorParams::phone)
        number(CreateAuthorParams::age)
        number(CreateAuthorParams::favouritePrimeNumber)
        populatedAfterSubmit(CreateAuthorParams::secretToken)
        //populatedAfterSubmit(CreateAuthorParams::favouriteColleague)
        hidden(CreateAuthorParams::favouriteColleague)
    }

    formBuilder.validate()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        routing {
            get("/") {
                val p = CreateAuthorParams(
                    firstName = FirstName(""),
                    lastName = LastName(""),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(99999),
                    //address = Address(Street("Storgatan"))
                    favouritePrimeNumber = PrimeNumber(31),
                )
                //authorizeAllDatatypes(p)

                val context = Context.swedishUnauthenticated()

                val form2 = klerk.read(context) {
                    formBuilder.build(call, p, this, translator = context.translation)
                }

                call.respondHtml {
                    head {
                        styleLink("https://unpkg.com/sakura.css/css/sakura.css")
                    }
                    body {
                        h1 { +"With server validation" }
                        +"Language: ${context.translation}"
                        form2.render(this)
                    }
                }
            }

            post("/") {
                when (val result = formBuilder.parse(
                    call,
                    mapOf(CreateAuthorParams::secretToken to SecretPasscode(1)),
                )) {
                    is Invalid -> EventFormTemplate.respondInvalid(result, call)
                    is DryRun -> respondDryRun<Author>(
                        result.params,
                        result.key,
                        CreateAuthor,
                        null,
                        call,
                        klerk
                    )

                    is Parsed -> call.respondHtml {
                        body {
                            h1 { +"Success" }
                            p { +result.params::class.qualifiedName.toString() }
                            result.params::class.memberProperties.forEach {
                                val value = it.getter.call(result.params) as DataContainer<*>
                                +"${it.name}: ${value.valueWithoutAuthorization}"
                                br
                            }
                        }
                    }
                }

            }
        }
        //        configureSecurity()
        //      configureHTTP()
    }.start(wait = true)
}

detta borde väl flyttas från test?
suspend fun <T : Any> respondDryRun(
    params: CreateAuthorParams,
    key: CommandToken,
    event: CreateAuthor,
    id: ModelID<Author>?,
    call: ApplicationCall,
    klerk: Klerk<Context, MyCollections>
) {
    val re = Command(
        event = event,
        params = params,
        model = id,
    )
    when (val result =
        klerk.handle(re, Context.swedishUnauthenticated(), ProcessingOptions(key, dryRun = true))) {
        is CommandResult.Failure -> {
            val fieldProblems = if (result.problem is InvalidPropertyProblem) {
                val p = result.problem as InvalidPropertyProblem
                mapOf(p.propertyName to (p.endUserTranslatedMessage ?: "?"))
            } else emptyMap()

            call.respondText(
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.UnprocessableEntity,
                text = Gson().toJson(
                    ValidationResponse(
                        fieldProblems = fieldProblems,
                        formProblems = emptyList(),
                        dryRunProblems = listOf(result.toString())
                    )
                )
            )
        }

        is CommandResult.Success -> call.respond(HttpStatusCode.OK)
    }
}

data class ValidationResponse(
    val fieldProblems: Map<String, String>,
    val formProblems: List<String>,
    val dryRunProblems: List<String>
)

private fun allowAll(dataContainer: DataContainer<*>, actorIdentity: ActorIdentity): Boolean {
    return true
}

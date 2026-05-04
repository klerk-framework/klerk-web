package dev.klerkframework.web

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.EventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.EventParameters
import dev.klerkframework.web.ParseResult.*
import dev.klerkframework.web.config.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
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

    val template = FormTemplate(
        EventWithParameters(
            CreateAuthor.id,
            EventParameters(CreateAuthorParams::class),
        ),
        klerk,
        "/",
        classProvider = ::myClassProvider,
    ) {
        text(CreateAuthorParams::firstName)
        text(CreateAuthorParams::lastName)
        text(CreateAuthorParams::phone)
        number(CreateAuthorParams::age)
        number(CreateAuthorParams::favouritePrimeNumber)
        populatedAfterSubmit(CreateAuthorParams::secretToken)
        //populatedAfterSubmit(CreateAuthorParams::favouriteColleague)
        hidden(CreateAuthorParams::favouriteColleague)
        remaining()
    }

    template.validate()

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
                    isLikedByMyDaughter = IsLikedByMyDaughter(false),
                    nullableFirstName = null,
               )

                //authorizeAllDatatypes(p)

                renderForm(call, klerk, template, null)
            }

            post("/") {
                when (val result = template.parse(
                    call,
                    mapOf(CreateAuthorParams::secretToken to SecretPasscode(1)),
                )) {
                    is Invalid -> FormTemplate.respondInvalid(result, call)
                    is DryRun -> respondDryRun(
                        result.params,
                        result.key,
                        CreateAuthor,
                        call,
                        klerk,
                        Context.swedishUnauthenticated(),
                    )
                    is Parsed -> renderForm(call, klerk, template, result.params, result)
                }

            }
        }
        //        configureSecurity()
        //      configureHTTP()
    }.start(wait = true)
}


/*suspend fun <T : Any> respondDryRun(
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

 */


private fun allowAll(dataContainer: DataContainer<*>, actorIdentity: ActorIdentity): Boolean {
    return true
}

fun myClassProvider(elementKind: String, elementType: String?, propertyName: String, value: String?): Set<String> {
    if (elementKind == "input" && elementType == "text" && propertyName == "firstName") {
        return setOf("testing")
    }
    return emptySet()
}

fun FlowOrMetaDataOrPhrasingContent.myFaviocn() {
    link(rel = "icon", href = "data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><text y=%22.9em%22 font-size=%2290%22>\uD83C\uDFAF</text></svg>")
}

suspend fun <T:Any> renderForm(
    call: ApplicationCall,
    klerk: Klerk<Context, MyCollections>,
    template: FormTemplate<T, Context>,
    params: T?,
    parseResult: Parsed<T>? = null
) {
    val context = Context.swedishUnauthenticated()
    val form2 = klerk.read(context) {
        template.build(call, params, this, translator = context.translation)
    }

    call.respondHtml {
        head {
            styleLink("https://unpkg.com/sakura.css@1.5.0/css/sakura.css")
            myFaviocn()
        }
        body {
            style {
                +"""
                            input:invalid {
                              background-color: ivory;
                              border: none;
                              outline: 2px solid red;
                              border-radius: 5px;
                            }
                            input.testing {
                                background-color: white;
                            }
                            span.input-error-message {
                                color: red;
                            }
                            span.errormessages {
                                color: red;
                            }
                            """.trimIndent()
            }
            h1 { +"With server validation" }
            +"Language: ${context.translation}"
            p {
                form2.render(this)
            }
            if (parseResult != null) {
                h1 { +"Success" }
                p { +parseResult.params::class.qualifiedName.toString() }
                parseResult.params::class.memberProperties.forEach {
                    val value = it.getter.call(parseResult.params) as? DataContainer<*>
                    +"${it.name}: ${value?.valueWithoutAuthorization ?: "null"}"
                    br
                }
            }
        }
    }
}
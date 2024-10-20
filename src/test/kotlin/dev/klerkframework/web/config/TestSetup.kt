package dev.klerkframework.web.config

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.ArgCommandContextReader
import dev.klerkframework.klerk.ArgContextReader
import dev.klerkframework.klerk.ArgForInstanceEvent
import dev.klerkframework.klerk.ArgForInstanceNonEvent
import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.ArgModelContextReader
import dev.klerkframework.klerk.ArgsForPropertyAuth
import dev.klerkframework.klerk.AuthenticationIdentity
import dev.klerkframework.klerk.Config
import dev.klerkframework.klerk.ConfigBuilder
import dev.klerkframework.klerk.DefaultTranslator
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.HumanReadable
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.InvalidParametersProblem
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.NegativeAuthorization.Pass
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.Translator
import dev.klerkframework.klerk.Unauthenticated
import dev.klerkframework.klerk.Validatable
import dev.klerkframework.klerk.Validity
import dev.klerkframework.klerk.Validity.Invalid
import dev.klerkframework.klerk.Validity.Valid
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.actions.Job
import dev.klerkframework.klerk.actions.JobContext
import dev.klerkframework.klerk.actions.JobId
import dev.klerkframework.klerk.actions.JobResult
import dev.klerkframework.klerk.collection.AllModelCollection
import dev.klerkframework.klerk.collection.FilteredModelCollection
import dev.klerkframework.klerk.collection.ModelCollection
import dev.klerkframework.klerk.collection.ModelCollections
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.FloatContainer
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.datatypes.GeoPositionContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.AlgorithmBuilder
import dev.klerkframework.klerk.misc.Decision
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.storage.SqlPersistence
import dev.klerkframework.web.config.AlwaysFalseDecisions.Something
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.sql.DriverManager
import kotlin.reflect.KProperty1
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import dev.klerkframework.web.config.AuthorStates.*

var onEnterAmateurStateActionCallback: (() -> Unit)? = null
var onEnterImprovingStateActionCallback: (() -> Unit)? = null

fun createConfig(collections: MyCollections, storage: Persistence = RamStorage()): Config<Context, MyCollections> {
    return ConfigBuilder<Context, MyCollections>(collections).build {
        persistence(storage)
        managedModels {
            model(Book::class, bookStateMachine(collections.authors.all, collections), collections.books)
            model(Author::class, authorStateMachine(collections), collections.authors)
            //model(Shop::class, cudStateMachine(Shop::class), views.shops)
        }
        authorization {
            readModels {
                positive {
                    rule(::`Everybody can read`)
                }
                negative {
                    rule(::pelleCannotReadOnMornings)
                    rule(::unauthenticatedCannotReadAstrid)
                }
            }

            readProperties {
                positive {
                    rule(::canReadAllProperties)
                }
                negative {
                    rule(::cannotReadAstrid)
                }
            }
            commands {
                positive {
                    rule(::`Everybody can do everything`)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(::`Everybody can read event log`)
                }
                negative {}
            }
        }
        contextProvider(::myContextProvider)
    }
}

fun myContextProvider(actorIdentity: ActorIdentity): Context {
    return Context(
        actor = actorIdentity,

        )
}

fun cannotReadAstrid(args: ArgsForPropertyAuth<Context, MyCollections>): NegativeAuthorization {
    return if (args.property is FirstName && args.property.valueWithoutAuthorization == "Astrid") Deny else Pass
}

fun canReadAllProperties(args: ArgsForPropertyAuth<Context, MyCollections>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}

fun unauthenticatedCannotReadAstrid(args: ArgModelContextReader<Context, MyCollections>): NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is Unauthenticated) Deny else Pass
}

fun `Everybody can do everything`(argCommandContextReader: ArgCommandContextReader<*, Context, MyCollections>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}


fun `Everybody can read event log`(args: ArgContextReader<Context, MyCollections>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}

fun `Everybody can read`(args: ArgModelContextReader<Context, MyCollections>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}

fun pelleCannotReadOnMornings(
    args: ArgModelContextReader<Context, MyCollections>
): NegativeAuthorization {
    try {
        if (args.context.user?.props?.name?.value.equals("Pelle")) {
            return if (args.context.time.toLocalDateTime(TimeZone.currentSystemDefault()).time < LocalTime.fromSecondOfDay(
                    3600 * 12
                )
            ) Deny else Pass
        }
    } catch (e: Exception) {
        //
    }
    return Pass
}

class BookCollections : ModelCollections<Book, Context>() {

    fun childrensBooks(): List<ModelID<Book>> {
        return emptyList()
    }
}

class AuthorCollections<V>(val allBooks: AllModelCollection<Book, Context>) : ModelCollections<Author, Context>() {

    private val greatAuthorNames = setOf("Linus", "Bertil")

    val greatAuthors = this.all.filter { greatAuthorNames.contains(it.props.firstName.value) }.register("greatAuthors")
    val establishedAuthors = this.all.filter { it.state == Established.name }.register("establishedAuthors")
    val establishedGreatAuthors =
        greatAuthors.filter { it.state == Established.name }.register("establishedGreatAuthors")
    lateinit var establishedGreatWithAtLeastTwoBooks: AuthorsWithAtLeastTwoBooks<V>

    val midrangeAuthors = this.all.filter {
        val i = it.props.lastName.value.toIntOrNull() ?: 0
        return@filter i in 15..24
    }

    override fun initialize() {
        establishedGreatWithAtLeastTwoBooks =
            AuthorsWithAtLeastTwoBooks(all, allBooks)
        establishedGreatWithAtLeastTwoBooks.register("medMinst2Böcker")
    }

}

data class Book(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>>,
    val previousBooksInSameSeries: List<ModelID<Book>>,
    val tags: Set<BookTag>,
    val salesPerYear: Set<Quantity>,
    val averageScore: AverageScore,
    val writtenAt: BookWrittenAt,
    val readingTime: ReadingTime,
    val publishedAt: BookWrittenAt?,
    val releasePartyPosition: ReleasePartyPosition,
) {
    override fun toString() = title.value
}

data class Author(val firstName: FirstName, val lastName: LastName, val address: Address) : Validatable {
    override fun validators(): Set<() -> Validity> = setOf(::noAuthorCanBeNamedJamesClavell)

    private fun noAuthorCanBeNamedJamesClavell(): Validity {
        return if (firstName.value == "James" && lastName.value == "Clavell") Invalid() else Valid
    }

    override fun toString(): String = "$firstName $lastName"
}

class ReleasePartyPosition(value: GeoPosition) : GeoPositionContainer(value)

//data class Shop(val shopName: ShopName, val owner: Reference<Author>) : CudModel

class ShopName(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

data class CreateAuthorParams(
    val firstName: FirstName,
    val lastName: LastName,
    val phone: PhoneNumber,
    val age: EvenIntContainer = EvenIntContainer(68),
    //  val address: Address,
    val secretToken: SecretPasscode,
    val favouriteColleague: ModelID<Author>? = null
) : Validatable {

    override fun validators(): Set<() -> Validity> = setOf(::augustStrindbergCannotHaveCertainPhoneNumber)

    @HumanReadable("Testar att namnge en funktion")
    private fun augustStrindbergCannotHaveCertainPhoneNumber(): Validity {
        return if (firstName.value == "August" && lastName.value == "Strindberg" && phone.value == "123456") Invalid() else Valid
    }
}

data class ChangeNameParams(val updatedFirstName: FirstName, val updatedLastName: LastName)

fun authorStateMachine(collections: MyCollections): StateMachine<Author, AuthorStates, Context, MyCollections> =

    stateMachine {

        event(CreateAuthor) {
            validateContext(::preventUnauthenticated)
            validateWithParameters(::cannotHaveAnAwfulName)
            validateWithParameters(::secretTokenShouldBeZeroIfNameStartsWithM)
            validateWithParameters(::onlyAuthenticationIdentityCanCreateDaniel)
            validReferences(CreateAuthorParams::favouriteColleague, collections.authors.all)
        }

        event(AnEventWithoutParameters) {}

        event(UpdateAuthor) {}

        event(ImproveAuthor) {}

        event(ChangeName) {}

        event(DeleteAuthor) {}

        event(DeleteAuthorAndBooks) {}


        voidState {
            onEvent(CreateAuthor) {
                createModel(Amateur, ::newAuthor)
            }

            onEvent(AnEventWithoutParameters) {
                createModel(Amateur, ::newAuthor2)
            }
        }

        state(Amateur) {
            onEnter {
                action(::onEnterAmateurStateAction)
            }

            onEvent(UpdateAuthor) {
                update(::updateAuthor)
            }

            onEvent(DeleteAuthor) {
                delete()
            }

            onEvent(DeleteAuthorAndBooks) {
                createCommands(::eventsToDeleteAuthorAndBooks)
            }

            onEvent(ImproveAuthor) {
                action(::showNotification)
                transitionTo(Improving)
            }

            onEvent(ChangeName) {
                update(::changeNameOfAuthor)
                job(::notifyBookStores)
            }

            after(30.seconds) {
                transitionTo(Established)
                update(::someUpdate)
                action(::sayHello)
            }

        }

        state(Improving) {
            onEnter {
                action(::onEnterImprovingStateAction)
                transitionWhen(
                    linkedMapOf(
                        ::isAnImpostor to Amateur,
                        ::hasTalent to Established,
                    )
                )
                job(::aJob)
            }

        }

        state(Established) {

            atTime(::later) {
                delete()
            }

            onEvent(ImproveAuthor) {
                action(::sayCongratulations)
            }

            onEvent(DeleteAuthor) {
                delete()
            }
        }

    }

fun sayCongratulations(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>) {
    println("Congrattulations")
}

fun someUpdate(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Author {
    return args.model.props.copy(lastName = LastName("efter"))
}

fun onExitUpdate(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Author {
    return args.model.props.copy(FirstName("Changed name after exit"))
}

fun sayHello(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    println("Hello!")
}

fun later(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Instant {
    return args.time.plus(30.seconds)
}

fun hasTalent(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Boolean = true
fun isAnImpostor(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): Boolean = false

fun aJob(args: ArgForInstanceNonEvent<Author, Context, MyCollections>): List<Job<Context, MyCollections>> {
    return listOf(MyJob)
}


fun onEnterImprovingStateAction(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    if (onEnterImprovingStateActionCallback != null) {
        onEnterImprovingStateActionCallback!!()
    }
}


fun showNotification(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>) {
    println("It was decided that we should show a notification")
}

fun onEnterAmateurStateAction(args: ArgForInstanceNonEvent<Author, Context, MyCollections>) {
    if (onEnterAmateurStateActionCallback != null) {
        onEnterAmateurStateActionCallback!!()
    }
}


fun notifyBookStores(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyCollections>): List<Job<Context, MyCollections>> {
    class MyJob : Job<Context, MyCollections> {
        override val id = 123L

        override suspend fun run(jobContext: JobContext<Context, MyCollections>): JobResult {
            println("Job started")
            return JobResult.Success
        }
    }

    return listOf(MyJob())
}

fun changeNameOfAuthor(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyCollections>): Author {
    return args.model.props.copy(
        firstName = args.command.params.updatedFirstName,
        lastName = args.command.params.updatedLastName
    )
}

fun eventsToDeleteAuthorAndBooks(args: ArgForInstanceEvent<Author, Nothing?, Context, MyCollections>): List<Command<Any, Any>> {
    args.reader.apply {
        val result: MutableList<Command<Any, Any>> = mutableListOf()
        val books = getRelated(Book::class, requireNotNull(args.model.id))

        @Suppress("UNCHECKED_CAST")
        books.map { Command(event = DeleteBook, model = it.id, null) }
            .forEach { result.add(it as Command<Any, Any>) }

        @Suppress("UNCHECKED_CAST")
        result.add(
            Command(event = DeleteAuthor, model = requireNotNull(args.model.id), null)
                    as Command<Any, Any>
        )

        return result
    }
}

fun newAuthor(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Author {
    val params = args.command.params
    return Author(
        firstName = params.firstName,
        lastName = params.lastName,
        address = Address(Street("kjh"))
    )
}

fun newAuthor2(args: ArgForVoidEvent<Author, Nothing?, Context, MyCollections>): Author {
    return Author(FirstName("Auto"), LastName("Created"), Address(Street("Somewhere")))
}


fun updateAuthor(args: ArgForInstanceEvent<Author, Author, Context, MyCollections>): Author {
    return args.command.params
}


fun onlyAuthenticationIdentityCanCreateDaniel(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    return if (args.command.params.firstName.value == "Daniel" && args.context.actor != AuthenticationIdentity) Invalid() else Valid
}

fun cannotHaveAnAwfulName(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    return if (args.command.params.firstName.value == "Mike" && args.command.params.lastName.value == "Litoris") Invalid() else Valid
}

fun secretTokenShouldBeZeroIfNameStartsWithM(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    return if (args.command.params.firstName.value.startsWith("M") && args.command.params.secretToken.value != 0L) Invalid() else Valid
}

fun preventUnauthenticated(context: Context): Validity {
    return if (context.actor == Unauthenticated) Invalid() else Valid
}

fun onlyAllowAuthorNameAstridIfThereIsNoRowling(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyCollections>): Validity {
    args.reader.apply {
        if (args.command.params.firstName.value != "Astrid") {
            return Valid
        }
        val rowling = firstOrNull(data.authors.all) { it.props.firstName.value == "Rowling" }
        return if (rowling == null) Valid else Invalid()
    }
}

fun newBook(args: ArgForVoidEvent<Book, CreateBookParams, Context, MyCollections>): Book {
    val params = args.command.params
    return Book(
        title = params.title,
        author = params.author,
        coAuthors = params.coAuthors,
        previousBooksInSameSeries = params.previousBooksInSameSeries,
        tags = params.tags,
        salesPerYear = setOf(Quantity(43), Quantity(67)),
        averageScore = params.averageScore,
        writtenAt = BookWrittenAt(Instant.fromEpochSeconds(100000)),
        readingTime = ReadingTime(23.hours),
        publishedAt = null,
        releasePartyPosition = ReleasePartyPosition(GeoPosition(latitude = 1.234, longitude = 3.456))
    )
}

enum class AuthorStates {
    Amateur,
    Improving,
    Established,
}

data class MyCollections(
    val books: BookCollections,
    val authors: AuthorCollections<MyCollections>
) //, val shops: ModelView<Shop, Context>)

suspend fun createAuthorJKRowling(klerk: Klerk<Context, MyCollections>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            event = CreateAuthor,
            model = null,
            params = CreateAuthorParams(
                firstName = FirstName("J.K"),
                lastName = LastName("Rowling"),
                phone = PhoneNumber("+46123456"),
                secretToken = SecretPasscode(234234902359245345),
                //       address = Address(Street("Storgatan"))
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    return requireNotNull(result.orThrow().primaryModel)
}

suspend fun createAuthorAstrid(klerk: Klerk<Context, MyCollections>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            event = CreateAuthor,
            model = null,
            params = createAstridParameters,
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    @Suppress("UNCHECKED_CAST")
    return result.orThrow().createdModels.single() as ModelID<Author>
}

val createAstridParameters = CreateAuthorParams(
    firstName = FirstName("Astrid"),
    lastName = LastName("Lindgren"),
    phone = PhoneNumber("+4699999"),
    secretToken = SecretPasscode(234123515123434),
)

suspend fun createBookHarryPotter1(klerk: Klerk<Context, MyCollections>, author: ModelID<Author>): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Philosopher's Stone"),
                author = author,
                coAuthors = emptySet(),
                previousBooksInSameSeries = emptyList(),
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f)
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple())
    )
    return requireNotNull(result.orThrow().primaryModel)
}

suspend fun createBookHarryPotter2(
    klerk: Klerk<Context, MyCollections>,
    author: ModelID<Author>,
    previousBooksInSameSeries: List<ModelID<Book>>,
    coAuthors: Set<ModelID<Author>>
): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Chamber of Secrets"),
                author = author,
                coAuthors = coAuthors,
                previousBooksInSameSeries = previousBooksInSameSeries,
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f)
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    return requireNotNull(result.orThrow().primaryModel)
}

class PhoneNumber(value: String) : StringContainer(value) {
    override val minLength = 3
    override val maxLength = 10
    override val maxLines: Int = 1
}

class EvenIntContainer(value: Int) : IntContainer(value) {
    override val min: Int = Int.MIN_VALUE
    override val max: Int = Int.MAX_VALUE

    override val validators = setOf(::mustBeEven)

    fun mustBeEven(): InvalidParametersProblem? {
        if (valueWithoutAuthorization % 2 == 0) {
            return null
        }
        return InvalidParametersProblem("Must be even")
    }

}

class FirstName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

class LastName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

class BookTitle(value: String) : StringContainer(value) {
    override val minLength = 2
    override val maxLength = 100
    override val maxLines: Int = 1
    override val regexPattern = ".*"
    override val validators = setOf(::`title must be catchy`)

    private fun `title must be catchy`(): InvalidParametersProblem? {
        return null
    }
}

class BookTag(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

class SecretPasscode(value: Long) : LongContainer(value) {
    override val min: Long = Long.MIN_VALUE
    override val max: Long = Long.MAX_VALUE
}

class IsActive(value: Boolean) : BooleanContainer(value)

class Quantity(value: Int) : IntContainer(value) {
    override val min: Int = 0
    override val max: Int = Int.MAX_VALUE
}

class BookWrittenAt(value: Instant) : InstantContainer(value) {

}

class ReadingTime(value: Duration) : DurationContainer(value)

data class Address(val street: Street)

class Street(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

fun addStandardTestConfiguration(auth: Boolean = true): ConfigBuilder<Context, MyCollections>.() -> Unit = {
    if (auth) {
        authorization {
            readModels {
                positive {
                    rule(::`Everybody can read`)
                }
                negative {
                    rule(::pelleCannotReadOnMornings)
                }
            }
            commands {
                positive {
                    rule(::`Everybody can do everything`)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(::`Everybody can read event log`)
                }
                negative {}
            }
        }
    }
}

/**
 * This is a hack to keep the SQLite connection open.
 * See https://github.com/JetBrains/Exposed/issues/726#issuecomment-932202379
 */
object SQLiteInMemory {
    private var keepAlive: Connection? = null

    fun create(): SqlPersistence {
        keepAlive?.close()
        val sqliteMemoryPath = "jdbc:sqlite:file:test?mode=memory&cache=shared"
        val ds = SQLiteDataSource()
        ds.url = sqliteMemoryPath
        keepAlive = DriverManager.getConnection(sqliteMemoryPath)
        return SqlPersistence(ds)
    }
}

object CreateAuthor :
    VoidEventWithParameters<Author, CreateAuthorParams>(Author::class, true, CreateAuthorParams::class)

object UpdateAuthor : InstanceEventWithParameters<Author, Author>(Author::class, true, Author::class) {

}

object DeleteAuthor : InstanceEventNoParameters<Author>(Author::class, true)

object DeleteAuthorAndBooks : InstanceEventNoParameters<Author>(Author::class, true)

object ImproveAuthor : InstanceEventNoParameters<Author>(Author::class, true)

object ChangeName : InstanceEventWithParameters<Author, ChangeNameParams>(Author::class, true, ChangeNameParams::class)

sealed class AlwaysFalseDecisions(
    override val name: String,
    override val function: (ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>) -> Boolean
) : Decision<Boolean, ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>> {
    data object Something : AlwaysFalseDecisions("This will always be false", ::alwaysFalse)

}

fun alwaysFalse(args: ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>): Boolean {
    return false
}


object AlwaysFalseAlgorithm :
    FlowChartAlgorithm<ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>, Boolean>("Always false") {

    override fun configure(): AlgorithmBuilder<ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyCollections>, Boolean>.() -> Unit =
        {
            start(Something)
            booleanNode(Something) {
                on(true, terminateWith = false)
                on(false, terminateWith = false)
            }
        }
}

data class Context(
    override val actor: dev.klerkframework.klerk.ActorIdentity,
    override val auditExtra: String? = null,
    override val time: Instant = Clock.System.now(),
    override val translator: Translator = DefaultTranslator(),
    val user: Model<User>? = null,
    val purpose: String = "Pass the butter",
) : KlerkContext {

    companion object {
        fun fromUser(user: Model<User>): Context {
            return Context(dev.klerkframework.klerk.ModelIdentity(user), user = user)
        }

        fun unauthenticated(): Context = Context(dev.klerkframework.klerk.Unauthenticated)

        fun authenticationIdentity(): Context = Context(dev.klerkframework.klerk.AuthenticationIdentity)

        fun system(): Context = Context(dev.klerkframework.klerk.SystemIdentity)

        fun swedishUnauthenticated(): Context = Context(dev.klerkframework.klerk.Unauthenticated, translator = SwedishTranslation())
    }

}

data class User(val name: FirstName)

object AnEventWithoutParameters : VoidEventNoParameters<Author>(Author::class, true)

object MyJob : Job<Context, MyCollections> {
    override val id: JobId
        get() = 999

    override suspend fun run(jobContext: JobContext<Context, MyCollections>): JobResult {
        println("Did MyJob")
        return JobResult.Success
    }

}

class SwedishTranslation : EnglishTranslation() {

    override fun property(property: KProperty1<*, *>): String {
        return when (property) {
            CreateAuthorParams::firstName -> "Förnamn på den nya författaren"
            else -> super.property(property)
        }
    }

    override fun event(event: EventReference): String {
        return when (event) {
            PublishBook.id -> "Publicera bok"
            CreateAuthor.id -> "Ny författare"
            else -> super.event(event)
        }
    }

}


open class EnglishTranslation : DefaultTranslator() {
    override fun property(property: KProperty1<*, *>): String {
        return when (property) {
            CreateAuthorParams::firstName -> "First name of the new writer"
            CreateAuthorParams::lastName -> "Last name of the new writer"
            else -> super.property(property)
        }
    }
}


object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(Book::class, true, CreateBookParams::class)

object PublishBook : InstanceEventNoParameters<Book>(Book::class, true)

object DeleteBook : InstanceEventNoParameters<Book>(Book::class, true)

data class CreateBookParams(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>> = emptySet(),
    val previousBooksInSameSeries: List<ModelID<Book>> = emptyList(),
    val tags: Set<BookTag> = emptySet(),
    val averageScore: AverageScore
)

class AverageScore(value: Float) : FloatContainer(value) {
    override val min: Float = 0f
    override val max: Float = Float.MAX_VALUE
}

class AuthorsWithAtLeastTwoBooks<V>(
    private val authors: ModelCollection<Author, Context>,
    private val books: AllModelCollection<Book, Context>,
) : ModelCollection<Author, Context>(authors) {

    override fun filter(filter: ((Model<Author>) -> Boolean)?): ModelCollection<Author, Context> {
        return if (filter == null) this else FilteredModelCollection(this, filter)
    }

    override fun <V> withReader(reader: Reader<Context, V>, cursor: QueryListCursor?): Sequence<Model<Author>> {
        return authors.withReader(reader, cursor).filter { author ->
            books.withReader(reader, null).filter { it.props.author == author.id }.take(2).count() == 2
        }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<Context, V>): Boolean {
        return withReader(reader, null).any { it.id == value }
    }

}

suspend fun generateSampleData(numberOfAuthors: Int, booksPerAuthor: Int, klerk: Klerk<Context, MyCollections>) {

    val startTime = kotlinx.datetime.Clock.System.now()
    val firstNames = setOf("Anna", "Bertil", "Janne", "Filip")
    val lastNames = setOf("Andersson", "Svensson", "Törnkrantz")
    val cities = setOf("Malmö, Göteborg, Falun", "Stockholm")

    for (i in 1..numberOfAuthors) {
        val result = klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName(firstNames.random()),
                    lastName = LastName(i.toString()),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(23290409),
                    //address = Address(Street("Lugna gatan"))
                ),
            ),
            Context.system(),
            ProcessingOptions(CommandToken.simple()),
        )

        if (i % 10 == 0) {
            klerk.handle(
                Command(
                    event = ImproveAuthor,
                    model = result.orThrow().primaryModel,
                    params = null
                ),
                context = Context.system(),
                ProcessingOptions(
                    CommandToken.simple()
                )
            )
        }

        val authorRef = requireNotNull(result.orThrow().primaryModel)
        println("Author: $authorRef")

        val author = klerk.read(Context.system()) { get(authorRef) }

        for (j in 1..booksPerAuthor) {
            klerk.handle(
                Command(
                    event = CreateBook,
                    model = null,
                    params = CreateBookParams(
                        title = BookTitle("Book $j"),
                        author = authorRef,
                        coAuthors = emptySet(),
                        previousBooksInSameSeries = emptyList(),
                        tags = setOf(BookTag("Fiction"), BookTag("Children")),
                        averageScore = AverageScore(0f)
                    ),
                ),
                Context.system(),
                ProcessingOptions(CommandToken.simple())
            )
        }
    }
    val seconds = startTime.minus(kotlinx.datetime.Clock.System.now()).inWholeSeconds
    val eventsPerSecond = if (seconds > 0) (numberOfAuthors * (booksPerAuthor + 1)) / seconds else "?"
}


enum class BookStates {
    Draft,
    Published,
}

fun bookStateMachine(allAuthors: ModelCollection<Author, Context>, collections: MyCollections): StateMachine<Book, BookStates, Context, MyCollections> =
    stateMachine {

        event(CreateBook) {
            validReferences(CreateBookParams::author, collections.authors.all)
        }

        event(PublishBook) {}

        event(DeleteBook) {}



        voidState {
            onEvent(CreateBook) {
                createModel(BookStates.Draft, ::newBook)
            }
        }

        state(BookStates.Draft) {
            onEnter {
                //action(`Send email to editors`)
            }

            onEvent(PublishBook) {
                update(::setPublishTime)
                transitionTo(BookStates.Published)
            }

            onEvent(DeleteBook) {
                delete()
            }

        }

        state(BookStates.Published) {

            onEvent(DeleteBook) {
                delete()
            }
        }

    }

fun setPublishTime(args: ArgForInstanceEvent<Book, Nothing?, Context, MyCollections>): Book {
    return args.model.props.copy(publishedAt = BookWrittenAt(args.context.time))
}


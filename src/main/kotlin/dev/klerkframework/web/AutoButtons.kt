package dev.klerkframework.web

import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID

import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FormMethod
import kotlinx.html.HtmlBlockTag
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.form
import kotlin.reflect.KClass

private val log = mu.KotlinLogging.logger {}

/**
 * Builds a [LowCodeCreateEvent] for every event with parameters that [eventFilter] admits.
 *
 * An event klerk-web cannot render a form for is reported here, when the application starts, and then skipped: no
 * button is generated for it, and the application is expected to render it itself. The alternative - discovering it
 * when a user clicks the button - is worse, and refusing to start is too harsh, since ordinary parameter shapes
 * (a collection of references, a nested value object, attached data) are still unsupported.
 */
internal fun <C : KlerkContext, V> buildCreateEvents(
    klerk: Klerk<C, V>,
    path: String,
    eventFilter: (EventReference) -> Boolean,
    create: (EventReference, KClass<out Any>) -> LowCodeCreateEvent<C, V>,
): List<LowCodeCreateEvent<C, V>> = klerk.config.managedModels.flatMap { managed ->
    managed.stateMachine.getAllEvents()
        .filter { klerk.config.getParameters(it) != null }
        .filter(eventFilter)
        .mapNotNull { event ->
            try {
                create(event, managed.kClass)
            } catch (e: IllegalStateException) {
                log.error {
                    "klerk-web will not generate a form for the event '$event': ${e.message} " +
                            "No button will be rendered for it - render it yourself if you need it."
                }
                null
            }
        }
}

/**
 * @param eventFilter which events to generate forms for. Events klerk-web cannot render a form for - for example
 * ones taking a collection of references, a nested value object or attached data - are skipped automatically and
 * reported at startup; use this to exclude further events that you want to render yourself.
 */
public class AutoButtons<C: KlerkContext, V>(
    internal val support: WebSupport<C, V>,
    internal val eventFilter: (EventReference) -> Boolean = { true },
) {
    internal val klerk: Klerk<C, V> get() = support.klerk
    internal val contextProvider: suspend (call: io.ktor.server.application.ApplicationCall, Klerk<C, V>) -> C
        get() = support.contextProvider
    internal val pathProvider: PathProvider get() = support.pathProvider

    private val createCommandsWithParams: List<LowCodeCreateEvent<C, V>> =
        buildCreateEvents(support.klerk, support.pathProvider.autoButtons, eventFilter) { event, kClass ->
            LowCodeCreateEvent(support, support.pathProvider.autoButtons, event, kClass, this@AutoButtons)
        }

    /** The events that klerk-web has a form for. Anything else must be rendered by the application. */
    internal val renderableEvents: Set<EventReference> = createCommandsWithParams.map { it.eventReference }.toSet()

    public fun registerRoutes(): Routing.() -> Unit = {
        get(pathProvider.autoButtons) {
            LowCodeCreateEvent.renderCreateEventPage(call, createCommandsWithParams, support)
        }

        post(pathProvider.autoButtons) {
            LowCodeCreateEvent.renderExecuteEvent(call, createCommandsWithParams, support)
        }

    }

    public fun render(
        event: Event<*, *>,
        modelId: ModelID<*>?,
        context: C,
        onCancelPath: String? = null,
        onSuccessAndModelExistPath: String? = null,
        onErrorPath: String? = null,
    ): HtmlBlockTag.() -> Unit = render(event.id, modelId, context, onCancelPath, onSuccessAndModelExistPath, onErrorPath)

    public fun render(
        event: EventReference,
        modelId: ModelID<*>?,
        context: C,
        onCancelPath: String? = null,
        onSuccessAndModelExistPath: String? = null,
        onErrorPath: String? = null,
    ): HtmlBlockTag.() -> Unit = block@{
        if (klerk.config.getParameters(event) != null && event !in renderableEvents) {
            // Excluded by the caller, or klerk-web cannot render a form for it (reported at startup). Either way the
            // application renders this event itself.
            return@block
        }
        val completionPaths = CompletionPaths(cancel = onCancelPath ?: "/", model = onSuccessAndModelExistPath ?: "/", error = onErrorPath ?: "/")
        var url =
            "${pathProvider.autoButtons}?eventId=${event.urlEncode()}&_showOptionalParameters=true&${completionPaths.toQueryParamsString()}"
        if (modelId != null) {
            url = url.plus("&modelId=${modelId}")
        }

        // Always a link to the AutoButtons page, also for events without parameters: that page renders the form,
        // which is where the CSRF token is issued. An event is never triggered by the button itself.
        a(url) {
            button {
                +context.translation.klerk.event(event)
            }
        }
    }

}

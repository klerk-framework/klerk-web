package dev.klerkframework.web

import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FormMethod
import kotlinx.html.FlowContent
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
): List<LowCodeCreateEvent<C, V>> = klerk.specification.managedModels.flatMap { managed ->
    managed.stateMachine.getAllEvents()
        .filter { klerk.specification.getParameters(it) != null }
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

    internal fun registerInto(route: Route) {
        route.get(pathProvider.autoButtons) {
            LowCodeCreateEvent.renderCreateEventPage(call, createCommandsWithParams, support)
        }
        route.post(pathProvider.autoButtons) {
            LowCodeCreateEvent.renderExecuteEvent(call, createCommandsWithParams, support)
        }
    }

    internal fun canRender(event: EventReference): Boolean =
        klerk.specification.getParameters(event) == null || event in renderableEvents

    internal fun urlFor(event: EventReference, modelId: ModelID<*>?, paths: CompletionPaths): String {
        var url =
            "${pathProvider.autoButtons}?eventId=${event.urlEncode()}&_showOptionalParameters=true&${paths.toQueryParamsString()}"
        if (modelId != null) {
            url = url.plus("&modelId=${modelId}")
        }
        return url
    }
}

/**
 * A button for an event. Clicking it leads to a page with a form; submitting that form issues the command.
 *
 * Renders nothing when klerk-web has no form for the event - either because it was excluded, or because it cannot
 * be rendered (which is reported when the application starts). Render such events yourself.
 *
 * @param onCancelPath where to go if the user cancels the form. Defaults to "/".
 * @param onSuccessAndModelExistPath where to go after a successful event, if the model still exists.
 * @param onErrorPath where to go if the event fails.
 */
context(support: WebSupport<C, V>)
public fun <C : KlerkContext, V> FlowContent.eventButton(
    event: Event<*, *>,
    modelId: ModelID<*>?,
    context: C,
    onCancelPath: String? = null,
    onSuccessAndModelExistPath: String? = null,
    onErrorPath: String? = null,
): Unit = eventButton(event.id, modelId, context, onCancelPath, onSuccessAndModelExistPath, onErrorPath)

/** As [eventButton], but takes the event by reference - e.g. what `getPossibleEvents` returns. */
context(support: WebSupport<C, V>)
public fun <C : KlerkContext, V> FlowContent.eventButton(
    event: EventReference,
    modelId: ModelID<*>?,
    context: C,
    onCancelPath: String? = null,
    onSuccessAndModelExistPath: String? = null,
    onErrorPath: String? = null,
) {
    val autoButtons = support.autoButtons
    if (!autoButtons.canRender(event)) {
        return
    }
    val completionPaths = CompletionPaths(
        cancel = onCancelPath ?: "/",
        model = onSuccessAndModelExistPath ?: "/",
        error = onErrorPath ?: "/",
    )
    // Always a link to the AutoButtons page, also for events without parameters: that page renders the form,
    // which is where the CSRF token is issued. An event is never triggered by the button itself.
    a(autoButtons.urlFor(event, modelId, completionPaths)) {
        button {
            +context.translation.klerk.event(event)
        }
    }
}

/** The routes that render an event's form and handle its submission. */
public fun <C : KlerkContext, V> Route.autoButtonsRoutes(autoButtons: AutoButtons<C, V>): Unit =
    autoButtons.registerInto(this)

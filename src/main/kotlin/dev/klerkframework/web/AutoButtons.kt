package dev.klerkframework.web

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

public class AutoButtons<C: KlerkContext, V>(
    internal val klerk: Klerk<C, V>,
    private val path: String,
    internal val contextProvider: suspend (call: io.ktor.server.application.ApplicationCall, Klerk<C, V>) -> C,
    internal val cssPath: String,
    internal val cssClassProvider: CssClassProvider? = null,
) {
    private val createCommandsWithParams: List<LowCodeCreateEvent<C, V>> = klerk.config.managedModels.flatMap { managed ->
        managed.stateMachine.getAllEvents().filter { klerk.config.getParameters(it) != null }.map { event ->
            LowCodeCreateEvent(klerk, path, event, managed.kClass)
        }
    }

    public fun registerRoutes(): Routing.() -> Unit = {
        get(path) {
            LowCodeCreateEvent.renderCreateEventPage(call, createCommandsWithParams, klerk, contextProvider, cssPath)
        }

        post(path) {
            LowCodeCreateEvent.renderExecuteEvent(call, createCommandsWithParams, klerk, contextProvider, cssPath)
        }

    }

    public fun render(
        event: EventReference,
        modelId: ModelID<*>?,
        context: C,
        onCancelPath: String? = null,
        onSuccessAndModelExistPath: String? = null,
        onErrorPath: String? = null,
    ): HtmlBlockTag.() -> Unit = {
        val completionPaths = CompletionPaths(cancel = onCancelPath ?: "/", model = onSuccessAndModelExistPath ?: "/", error = onErrorPath ?: "/")
        val parameters = klerk.config.getParameters(event)
        var url =
            "/$path?eventId=${event.urlEncode()}&_showOptionalParameters=true&${completionPaths.toQueryParamsString()}"
        if (modelId != null) {
            url = url.plus("&modelId=${modelId}")
        }

        if (parameters == null) {
            // TODO: remove this! Only use a link to the next page and render an "empty" form.
            form(action = url, method = FormMethod.post) { button { +context.translation.klerk.event(event) } }
        } else {
            a(url) {
                button {
                    +context.translation.klerk.event(event)
                }
            }
        }
    }

}

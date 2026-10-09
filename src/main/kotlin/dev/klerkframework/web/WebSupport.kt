package dev.klerkframework.web

import dev.klerkframework.klerk.AttachedDataMetadata
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.web.attached.attachedDataRoutes
import dev.klerkframework.web.attached.defaultAttachedDataCacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.HEAD

/**
 * What every klerk-web building block needs: Klerk, a way to get a context from a call, where pages live, what they
 * look like, and how they are styled.
 *
 * Pass the same instance to every block so that the pages link to each other and look alike. The Admin UI uses one
 * of its own, with an "admin/" prefix - see [withPathProvider].
 */
public class WebSupport<C : KlerkContext, V>(
    /** The application. */
    public val klerk: Klerk<C, V>,
    /**
     * Builds the context for a call. See
     * [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/introduction.md).
     */
    public val contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C,
    /** Where pages live. */
    public val pathProvider: PathProvider = DefaultPathProvider(),
    /** What pages look like. */
    public val layout: Layout = Layout(assetsBase = pathProvider.assetsBase),
    /** How elements are styled. Leave it out when using a classless CSS. */
    public val classProvider: CssClassProvider? = null,
    /** Which events to generate forms for, see [AutoButtons]. */
    public val eventFilter: (EventReference) -> Boolean = { true },
    /**
     * The `Cache-Control` [attachedDataRoutes] sends for a value, decided from its metadata — its visibility, its
     * size, its `custom` entries. See [defaultAttachedDataCacheControl].
     */
    public val attachedDataCacheControl: (AttachedDataMetadata) -> String = defaultAttachedDataCacheControl,
) {
    /** Renders a button for an event, and the form behind it. */
    public val autoButtons: AutoButtons<C, V> by lazy { AutoButtons(this, eventFilter) }

    /** The same, but for pages mounted somewhere else. */
    public fun withPathProvider(other: PathProvider): WebSupport<C, V> =
        WebSupport(klerk, contextProvider, other, layout, classProvider, eventFilter, attachedDataCacheControl)

    /**
     * Responds with a complete page produced by [layout].
     *
     * This support is a context inside [block], so the building blocks can be called directly:
     *
     * ```kotlin
     * support.respondPage(call, "Authors") {
     *     h1 { +"Authors" }
     *     modelTable(table)
     *     eventButton(event, id, context)
     * }
     * ```
     *
     * To render a fragment instead of a whole page, use `with(support) { ... }`. [pageHead] is rendered last in
     * `<head>`, for what only this page needs. Without a [status], the one already set on the call is used (e.g. 400
     * from [TableTemplate.build]), or 200.
     */
    public suspend fun respondPage(
        call: ApplicationCall,
        title: String,
        status: HttpStatusCode? = null,
        pageHead: (HEAD.() -> Unit)? = null,
        block: context(WebSupport<C, V>) FlowContent.() -> Unit,
    ) {
        call.respondHtml(
            status = status ?: call.response.status() ?: HttpStatusCode.OK,
            block = layout.page(title, pageHead) { block(this@WebSupport, this) },
        )
    }
}

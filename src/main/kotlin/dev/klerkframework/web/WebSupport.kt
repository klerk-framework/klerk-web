package dev.klerkframework.web

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
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
 *
 * @param contextProvider see [the documentation](https://github.com/klerkframework/klerk-web/blob/main/docs/introduction.md).
 * @param classProvider optional; leave it out when using a classless CSS.
 * @param eventFilter which events to generate forms for, see [AutoButtons].
 */
public class WebSupport<C : KlerkContext, V>(
    public val klerk: Klerk<C, V>,
    public val contextProvider: suspend (call: ApplicationCall, Klerk<C, V>) -> C,
    public val pathProvider: PathProvider = DefaultPathProvider(),
    public val layout: Layout = Layout(assetsBase = pathProvider.assetsBase),
    public val classProvider: CssClassProvider? = null,
    public val eventFilter: (EventReference) -> Boolean = { true },
) {
    /** Renders a button for an event, and the form behind it. */
    public val autoButtons: AutoButtons<C, V> by lazy { AutoButtons(this, eventFilter) }

    /** The same, but for pages mounted somewhere else. */
    public fun withPathProvider(other: PathProvider): WebSupport<C, V> =
        WebSupport(klerk, contextProvider, other, layout, classProvider, eventFilter)

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
     * To render a fragment instead of a whole page, use `with(support) { ... }`.
     *
     * @param pageHead rendered last in `<head>`, for what only this page needs.
     */
    public suspend fun respondPage(
        call: ApplicationCall,
        title: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        pageHead: (HEAD.() -> Unit)? = null,
        block: context(WebSupport<C, V>) FlowContent.() -> Unit,
    ) {
        call.respondHtml(status = status, block = layout.page(title, pageHead) { block(this@WebSupport, this) })
    }
}

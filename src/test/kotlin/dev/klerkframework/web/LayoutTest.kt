package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertTrue

private suspend fun ApplicationCall.layoutCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

/**
 * Generated pages must be valid documents: klerk-web used to emit a `<head>` containing nothing but a stylesheet
 * link - no title, no lang, no viewport.
 */
class LayoutTest {

    private fun setup(): Pair<Klerk<Context, MyCollections>, KlerkWeb<Context, MyCollections>> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val klerk = Klerk.create(createConfig(collections))
        val layout = Layout(externalCssPath = "https://example.com/classless.css", lang = "sv")
        return Pair(klerk, KlerkWeb(klerk, ApplicationCall::layoutCtx, canSeeAdminUI = { true }, layout = layout))
    }

    @Test
    fun `generated pages are complete documents`() = testApplication {
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()
        createAuthorJKRowling(klerk)

        application { routing { klerkWebRoutes(klerkWeb) } }

        listOf("/author", "/admin/", "/admin/_jobs").forEach { path ->
            val body = client.get(path).bodyAsText()
            assertTrue(body.contains("""<html lang="sv""""), "$path: no lang")
            assertTrue(body.contains("<title>"), "$path: no title")
            assertTrue(body.contains("""name="viewport""""), "$path: no viewport")
            assertTrue(body.contains("https://example.com/classless.css"), "$path: no stylesheet")
        }
    }

    @Test
    fun `the layout is used by the admin ui too, so one stylesheet covers both`() = testApplication {
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()

        application { routing { klerkWebRoutes(klerkWeb) } }

        // The admin pages are mounted under a different PathProvider but share the Layout.
        val admin = client.get("/admin/_documentation").bodyAsText()
        assertTrue(admin.contains("https://example.com/classless.css"))
    }
}

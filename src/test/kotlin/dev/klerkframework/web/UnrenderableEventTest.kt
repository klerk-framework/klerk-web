package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.web.config.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun ApplicationCall.sysCtx(klerk: Klerk<Context, MyCollections>): Context = Context.system()

/**
 * An event whose parameters klerk-web cannot render a form for must not stop the application from starting. It is
 * reported when the routes are built, then skipped: no button, and its URL answers 404.
 */
class UnrenderableEventTest {

    private fun setup(): Pair<Klerk<Context, MyCollections>, KlerkWeb<Context, MyCollections>> {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val klerk = Klerk.create(createConfig(collections), testSettings())
        // CreateBook takes a Set<ModelID<Author>>, UpdateAuthor a nested Address, CreateTextAsset an AttachedDataRef.
        return Pair(klerk, KlerkWeb(klerk, ApplicationCall::sysCtx, canSeeAdminUI = { true }))
    }

    @Test
    fun `the application starts and the unrenderable event gets no button`() = testApplication {
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()
        val author = createAuthorJKRowling(klerk)

        application { routing { klerkWebRoutes(klerkWeb) } }

        // A renderable event is still offered.
        val authorPage = client.get("/admin/author/${author.value}").bodyAsText()
        assertTrue(authorPage.contains("eventId="), "Expected at least one event button")

        // The unrenderable one is not.
        assertFalse(
            authorPage.contains(UpdateAuthor.id.urlEncode()),
            "UpdateAuthor cannot be rendered, so it must not get a button"
        )
    }

    @Test
    fun `the unrenderable event's form page answers 404`() = testApplication {
        val (klerk, klerkWeb) = setup()
        klerk.meta.start()

        application { routing { klerkWebRoutes(klerkWeb) } }

        val response = client.get("/_autobuttons?eventId=${UpdateAuthor.id.urlEncode()}")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

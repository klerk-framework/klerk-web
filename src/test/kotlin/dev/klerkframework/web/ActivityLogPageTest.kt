package dev.klerkframework.web

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.view.ModelViews
import dev.klerkframework.web.config.AuthorCollections
import dev.klerkframework.web.config.BookCollections
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.MyCollections
import dev.klerkframework.web.config.createConfig
import dev.klerkframework.web.config.testSettings
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityLogPageTest {

    private suspend fun klerkWeb(context: Context): KlerkWeb<Context, MyCollections> {
        val bc = BookCollections()
        val collections = MyCollections(bc, AuthorCollections(bc.all), ModelViews())
        val klerk = Klerk.create(createConfig(collections), testSettings())
        val web = KlerkWeb(klerk, { _, _ -> context }, canSeeAdminUI = { true })
        klerk.meta.start()
        return web
    }

    @Test
    fun `the activity log page is forbidden without an activityLog rule`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val web = klerkWeb(Context.authenticationIdentity())
        application { routing { klerkWebRoutes(web) } }

        assertEquals(HttpStatusCode.Forbidden, client.get("/admin/_log").status)
    }

    @Test
    fun `the system can see the activity log page`() = testApplication {
        System.setProperty("DEVELOPMENT_MODE", "true")
        val web = klerkWeb(Context.system())
        application { routing { klerkWebRoutes(web) } }

        val response = client.get("/admin/_log")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Activity log"))
    }
}

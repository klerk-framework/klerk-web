package dev.klerkframework.web

import dev.klerkframework.web.config.Book
import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class PathProviderTest {

    @Test
    fun defaultPathProvider() {
        var dpp = DefaultPathProvider()
        assertEquals("/book", dpp.pathForCollection(Book::class))
        assertEquals("/book/123", dpp.pathForItem(Book::class, "123"))
        assertEquals("/_assets/test", dpp.assetPath("test"))
        assertEquals(null, dpp.cssUrl())
        assertEquals("/_autobuttons", dpp.autoButtons)

        dpp = DefaultPathProvider("/base/")
        assertEquals("/base/book", dpp.pathForCollection(Book::class))
        assertEquals("/base/book/123", dpp.pathForItem(Book::class, "123"))
        assertEquals("/base/_assets/test", dpp.assetPath("test"))
        assertEquals("/base/_autobuttons", dpp.autoButtons)

        dpp = DefaultPathProvider("/base/", "prefix/")
        assertEquals("/base/prefix/book", dpp.pathForCollection(Book::class))
        assertEquals("/base/prefix/book/123", dpp.pathForItem(Book::class, "123"))
        assertEquals("/base/_assets/test", dpp.assetPath("test"))
        assertEquals("/base/_autobuttons", dpp.autoButtons)
    }

}

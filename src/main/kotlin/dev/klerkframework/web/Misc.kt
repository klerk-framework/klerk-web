package dev.klerkframework.web


import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.klerk.read.Reader
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.utils.io.charsets.Charsets
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format.char
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import java.security.SecureRandom
import kotlin.reflect.KClass
import kotlin.reflect.KType

internal val secureRandom = SecureRandom.getInstanceStrong()


/** Refreshes the page every [seconds] seconds. */
internal fun autoRefresh(seconds: Int): HEAD.() -> Unit = {
    meta { httpEquiv = "refresh"; content = seconds.toString() }
}


internal fun isModelId(type: KType): Boolean {
    return type.toString().startsWith(ModelID::class.qualifiedName!!, false)
}

internal fun FlowContent.breadcrumbs(clazz: KClass<out Any>, pathProvider: PathProvider, isDetails: Boolean = false ): Unit = nav {
    val name = camelCaseToPretty(requireNotNull(clazz.simpleName))
    a(href = pathProvider.withPrefix()) { +"Home" }
    unsafe {
        +"$NON_BREAKING_SPACE>$NON_BREAKING_SPACE"
    }
    if (isDetails) {
        a(href = pathProvider.pathForCollection(clazz)) { +name }
        unsafe {
            +"$NON_BREAKING_SPACE>$NON_BREAKING_SPACE"
        }
        +"Details"
    } else {
        +name
    }
}

private val allAllowed = "abcdefghijklmnopqrstuvwxyzABCDEFGJKLMNPRSTUVWXYZ0123456789".toCharArray()

internal fun generateRandomString(): String {
    val builder = StringBuilder()
    for (i in 0 until 20) {
        builder.append(allAllowed[secureRandom.nextInt(allAllowed.size)])
    }
    return builder.toString()
}

/** True when the DEVELOPMENT_MODE property or environment variable is "true". Relaxes cookie requirements. */
public fun isDevelopmentMode(): Boolean {
    return System.getenv("DEVELOPMENT_MODE")?.lowercase() == "true" ||
            System.getProperty("DEVELOPMENT_MODE")?.lowercase() == "true"
}

internal val dateFormatter = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
}

/** The format an `<input type="datetime-local">` uses. */
internal val dateTimeLocalFormat = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
    char('T')
    hour()
    char(':')
    minute()
}

internal val dateTimeFormatter = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
}

/**
 * Human-readable rendering of the temporal containers, whose stored value is a number (epoch day or
 * microseconds). Returns null for anything else, so the caller can fall back to its usual rendering.
 */
internal fun renderTemporalContainer(value: Any?): String? = when (value) {
    is DateContainer -> value.ifAuthorized { dateFormatter.format(date.toKotlinLocalDate().atTime(0, 0)) }
    is InstantContainer -> value.ifAuthorized {
        dateTimeFormatter.format(instant.toLocalDateTime(TimeZone.currentSystemDefault()))
    }
    is DurationContainer -> value.ifAuthorized { duration.toString() }
    else -> null
}

/**
 * The container's `date`/`instant`/`duration` fields are readable even when the property is masked, so the
 * authorization of this particular container decides whether [render] may be used at all.
 */
private fun <T : DataContainer<*>> T.ifAuthorized(render: T.() -> String): String =
    if (valueOrNullIfNotAuthorized == null) toString() else render()


/** Produces a complete HTML document inside a read. Use [Layout.page] to get the same head as the generated pages. */
public fun <C : KlerkContext, V> Reader<C, V>.html(status: HttpStatusCode = HttpStatusCode.OK, block: HTML.() -> Unit) : TextContent {
    val text = buildString {
        append("<!DOCTYPE html>\n")
        appendHTML().html(block = block)
    }
    return TextContent(text, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

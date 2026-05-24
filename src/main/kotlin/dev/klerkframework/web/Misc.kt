package dev.klerkframework.web


import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.misc.camelCaseToPretty
import dev.klerkframework.web.assets.CssAsset
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char
import kotlinx.html.*
import java.security.SecureRandom
import kotlin.reflect.KClass
import kotlin.reflect.KType

internal val secureRandom = SecureRandom.getInstanceStrong()


internal fun <C : KlerkContext, V> lowCodeHtmlHead(config: AdminUI<C, V>): HTML.() -> Unit =
    lowCodeHtmlHead(config.pathProvider)

internal fun lowCodeHtmlHead(pathProvider: PathProvider): HTML.() -> Unit = {
    head {
        //meta(name = "viewport", content = "width=device-width, initial-scale=1")
        pathProvider.cssUrl()?.let { styleLink(it) }
    }
}


internal fun isModelId(type: KType): Boolean {
    return type.toString().startsWith(ModelID::class.qualifiedName!!, false)
}

internal fun BODY.breadcrumbs(clazz: KClass<out Any>, pathProvider: PathProvider, isDetails: Boolean = false ): Unit = nav {
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

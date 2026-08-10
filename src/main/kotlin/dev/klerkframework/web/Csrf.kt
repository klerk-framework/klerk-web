package dev.klerkframework.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import kotlinx.html.FORM
import kotlinx.html.body
import kotlinx.html.hiddenInput
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * CSRF protection for every POST that klerk-web registers.
 *
 * The 'Double Submit Pattern' with the '__Host-' cookie prefix is used: the token is stored in a cookie and repeated
 * in a hidden form field, and the two must match. The token is per session rather than per form, so that several
 * forms can live on the same page and several tabs can be open at once.
 */
internal object Csrf {

    internal val TOKEN_NAME: String = if (isDevelopmentMode()) "csrf-token" else "__Host-csrf-token"

    private const val MAX_AGE_SECONDS = 3600L

    private val issuedTokenKey = AttributeKey<String>("klerk-csrf-issued")

    /**
     * The token to put in a form. Reuses the token the browser already has, so that building several forms during one
     * request does not invalidate the earlier ones.
     */
    internal fun issue(call: ApplicationCall): String {
        val existing = call.request.cookies[TOKEN_NAME]
        if (existing != null) {
            return existing
        }
        // Several forms may be built while handling one request. They must share one token and one Set-Cookie.
        call.attributes.getOrNull(issuedTokenKey)?.let { return it }
        val token = generateRandomString()
        call.attributes.put(issuedTokenKey, token)
        try {
            call.response.cookies.append(
                Cookie(
                    name = TOKEN_NAME,
                    value = token,
                    secure = !isDevelopmentMode(),
                    httpOnly = true,
                    path = "/",
                    maxAge = MAX_AGE_SECONDS.toInt(),
                    extensions = mapOf("SameSite" to "Strict"),
                )
            )
        } catch (e: UnsupportedOperationException) {
            log.error { "The form must be built before call.respond is called" }
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("HTTPS") == true) {
                log.error { "Did you forget to set the property/environment variable DEVELOPMENT_MODE=true ?" }
            }
            throw e
        }
        return token
    }

    /** Renders the hidden input. Must be placed before any non-hidden input. */
    internal fun FORM.tokenInput(token: String) {
        hiddenInput(name = TOKEN_NAME) { value = token }
    }

    /**
     * Whether the request may be accepted. [submittedToken] is the value of the hidden form field.
     */
    internal fun isValid(call: ApplicationCall, submittedToken: String?): Boolean {
        val cookie = call.request.cookies[TOKEN_NAME]
        if (cookie == null || submittedToken == null || cookie != submittedToken) {
            log.info { "CSRF check failed" }
            return false
        }
        return true
    }

    /**
     * Verifies the request and responds 403 if it fails. Returns true when the caller may proceed.
     */
    internal suspend fun verifyOrRespond(call: ApplicationCall, submittedToken: String?): Boolean {
        if (isValid(call, submittedToken)) {
            return true
        }
        call.respondHtml(status = HttpStatusCode.Forbidden) {
            body { +"The request could not be verified. Please reload the page and try again." }
        }
        return false
    }

    /**
     * Reads the parameters of a POST and verifies the token in one go, responding 403 if it fails.
     * Returns null when the caller must stop.
     */
    internal suspend fun receiveVerifiedParameters(call: ApplicationCall): Parameters? {
        val params = call.receiveParameters()
        return if (verifyOrRespond(call, params[TOKEN_NAME])) params else null
    }
}

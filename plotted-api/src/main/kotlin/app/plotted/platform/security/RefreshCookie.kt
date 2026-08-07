package app.plotted.platform.security

import app.plotted.platform.config.PlottedProperties
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

/**
 * The one definition of how the refresh cookie is set and cleared.
 *
 * In the shared kernel rather than in `identity` because demo mode issues
 * sessions too, and two copies of this policy is a security bug waiting for
 * someone to change `secure` or `sameSite` in one of them. The attributes here
 * are the whole of the cookie's protection; there is no second place that
 * enforces them.
 *
 * The refresh token is the long-lived credential, so it is kept out of
 * JavaScript's reach entirely and scoped to the auth path so it is not attached
 * to every API call. The access token travels in the response body and is
 * expected to live in memory only.
 */
@Component
class RefreshCookie(private val properties: PlottedProperties) {
    fun issue(token: String): ResponseCookie = base(token)
        .maxAge(properties.security.jwt.refreshTokenTtl)
        .build()

    fun cleared(): ResponseCookie = base("").maxAge(0).build()

    private fun base(value: String) = ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(properties.security.jwt.refreshCookieSecure)
        .sameSite("Lax")
        .path(PATH)

    companion object {
        const val NAME = "plotted_refresh"

        /** Scoped so the cookie is not attached to every API call. */
        const val PATH = "/api/v1/auth"
    }
}

package app.plotted.platform.security

import app.plotted.platform.spi.AccountDirectory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the bearer access token and populates the security context. An absent or
 * unverifiable token leaves the context empty; the authorisation rules in
 * [app.plotted.platform.config.SecurityConfig] decide whether that is a problem,
 * so this filter never writes a response itself.
 *
 * ### Why a verified signature is not enough
 *
 * A signature proves the token was issued here and has not expired. It says
 * nothing about whether the account still exists, and Plotted deletes accounts
 * on a schedule: the demo sweep runs hourly, so at any moment some visitor is
 * holding a perfectly valid token for an account that was removed minutes ago.
 *
 * Left to each endpoint, that produced four different answers to one condition,
 * including a 500 — see [AccountDirectory]. Resolving it here means the whole
 * API refuses identically, with the 401 that is actually true: this is not a
 * server fault and it is not a permission problem, the caller is no longer
 * anybody.
 *
 * The cost is one primary-key `EXISTS` per authenticated request, and only when
 * a token verifies — unauthenticated traffic, health checks and the login and
 * refresh endpoints carry no bearer token and do not pay it.
 *
 * Measured on 2026-08-08 by taking this one line out and putting it back, against
 * `GET /api/v1/tonight`, 200 warmed sequential requests per run: **with** the
 * check, medians of 21.0, 22.3 and 21.3 ms; **without** it, 23.5 and 21.4 ms. The
 * arms overlap, so the defensible statement is not that the check is free but
 * that it is **smaller than this rig's run-to-run spread** and cannot be resolved
 * here. These runs were against a `bootRun` with devtools and jOOQ debug logging
 * on, which is why they sit above the 15.8 ms recorded in `PROGRESS.md` and are
 * not comparable to it; a number worth quoting needs a deployed environment.
 *
 * Tokens stay stateless in the sense that mattered: nothing is stored per
 * session, and revocation still lives with the refresh family in Postgres.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val accounts: AccountDirectory,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        bearerToken(request)
            ?.let(jwtService::verify)
            ?.takeIf { accounts.exists(it.userId) }
            ?.let { user ->
                SecurityContextHolder.getContext().authentication = PlottedAuthentication(user)
            }
        filterChain.doFilter(request, response)
    }

    private fun bearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return header.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}

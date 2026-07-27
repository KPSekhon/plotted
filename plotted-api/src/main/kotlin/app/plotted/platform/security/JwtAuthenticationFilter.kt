package app.plotted.platform.security

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
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        bearerToken(request)
            ?.let(jwtService::verify)
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

package app.plotted.platform.security

import app.plotted.platform.spi.AccountDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * What the filter is allowed to believe about a bearer token.
 *
 * A verified signature is evidence the token was issued here and has not
 * expired. It is not evidence the account still exists, and Plotted deletes
 * accounts on a schedule — the demo sweep runs hourly, so a valid token for a
 * deleted account is a routine condition rather than a hypothetical one.
 *
 * Before the account check the condition had no single answer. `/alerts`
 * returned an empty list, `/users/me/settings` a 404, `/pilot/profile` a 204,
 * and `/watchlist` a 500 from a foreign-key violation. These tests pin the one
 * answer: the context stays empty and the entry point renders 401.
 */
class JwtAuthenticationFilterTest {
    private val jwtService = mockk<JwtService>()
    private val accounts = mockk<AccountDirectory>()
    private val filter = JwtAuthenticationFilter(jwtService, accounts)

    private val userId = UUID.randomUUID()
    private val user = AuthenticatedUser(userId, "someone@example.invalid")

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a verified token for a live account authenticates`() {
        every { jwtService.verify("good") } returns user
        every { accounts.exists(userId) } returns true

        val chain = runFilter(bearer = "good")

        authenticatedUser() shouldBe user
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `a verified token for an account that no longer exists does not authenticate`() {
        every { jwtService.verify("orphan") } returns user
        every { accounts.exists(userId) } returns false

        val chain = runFilter(bearer = "orphan")

        // Empty context, not an exception. The filter chain runs before the
        // controller advice, so throwing here would escape it and reach the
        // container as a 500 — the failure this whole change exists to remove.
        authenticatedUser().shouldBeNull()
        verify { chain.doFilter(any(), any()) }
    }

    @Test
    fun `an unverifiable token never reaches the account lookup`() {
        every { jwtService.verify("forged") } returns null

        runFilter(bearer = "forged")

        authenticatedUser().shouldBeNull()
        verify(exactly = 0) { accounts.exists(any()) }
    }

    /**
     * The cost of the check is the reason it is worth pinning that
     * unauthenticated traffic does not pay it. Health checks, the login and
     * refresh endpoints and every anonymous request carry no bearer token, and
     * none of them should be querying the users table.
     */
    @Test
    fun `a request with no bearer token queries nothing`() {
        runFilter(bearer = null)

        authenticatedUser().shouldBeNull()
        verify(exactly = 0) { jwtService.verify(any()) }
        verify(exactly = 0) { accounts.exists(any()) }
    }

    @Test
    fun `an Authorization header that is not a bearer token queries nothing`() {
        runFilter(header = "Basic dXNlcjpwYXNz")

        authenticatedUser().shouldBeNull()
        verify(exactly = 0) { jwtService.verify(any()) }
        verify(exactly = 0) { accounts.exists(any()) }
    }

    /**
     * A real [MockHttpServletRequest] rather than a mocked one:
     * `OncePerRequestFilter` reads a request *attribute* to decide whether it has
     * already run, and a strict mock has no answer for it. The filter would then
     * fail for a reason that has nothing to do with what is being asserted.
     */
    private fun runFilter(bearer: String? = null, header: String? = bearer?.let { "Bearer $it" }): FilterChain {
        val request = MockHttpServletRequest()
        header?.let { request.addHeader(HttpHeaders.AUTHORIZATION, it) }
        val chain = mockk<FilterChain>(relaxed = true)
        filter.doFilter(request, MockHttpServletResponse(), chain)
        return chain
    }

    private fun authenticatedUser(): AuthenticatedUser? =
        (SecurityContextHolder.getContext().authentication as? PlottedAuthentication)?.user
}

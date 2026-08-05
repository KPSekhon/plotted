package app.plotted.identity

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Runs against a real PostgreSQL 16, because most of what is being verified here
 * lives in the schema: the case-insensitive unique email, the NOT NULL defaults,
 * and the fenced DDL that the jOOQ generator never sees. An in-memory database
 * would test a different schema than the one that ships.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class AuthenticationFlowIntegrationTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    /**
     * Swap the default `HttpURLConnection` transport for the JDK HTTP client.
     *
     * Two of its limitations break this suite rather than the application:
     * `HttpURLConnection` cannot send PATCH at all, and on a 401 it tries to
     * follow an authentication challenge, which fails as `HttpRetryException`
     * once the request body has been streamed. Both surface as transport
     * exceptions in place of the response the test wants to assert on, which
     * looks like an API fault and is not one.
     */
    @BeforeEach
    fun useJdkHttpClient() {
        rest.restTemplate.requestFactory = JdkClientHttpRequestFactory()
    }

    @Test
    fun `migrations apply and a new account can be created and read back`() {
        val email = uniqueEmail()

        val registration = register(email)

        registration.statusCode shouldBe HttpStatus.CREATED
        val session = registration.body!!
        session["accessToken"] shouldNotBe null
        (session["user"] as Map<*, *>)["email"] shouldBe email
        (session["user"] as Map<*, *>)["regionCode"] shouldBe "CA"
        (session["user"] as Map<*, *>)["preferredCurrency"] shouldBe "CAD"
        // The refresh token is HttpOnly and must never appear in the body.
        session.containsKey("refreshToken") shouldBe false

        val me = get("/api/v1/users/me", accessToken(registration.body!!))

        me.statusCode shouldBe HttpStatus.OK
        me.body!!["email"] shouldBe email
    }

    @Test
    fun `the refresh token is issued as an HttpOnly cookie`() {
        val registration = register(uniqueEmail())

        val cookie = registration.headers[HttpHeaders.SET_COOKIE]!!.single()

        cookie shouldContain "plotted_refresh="
        cookie shouldContain "HttpOnly"
        cookie shouldContain "SameSite=Lax"
        cookie shouldContain "Path=/api/v1/auth"
    }

    @Test
    fun `an unauthenticated request is refused as an RFC 9457 problem`() {
        val response = rest.exchange(
            "/api/v1/users/me",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )

        response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        response.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe true
        response.body!!["code"] shouldBe "AUTHENTICATION_REQUIRED"
        response.body!!["type"].toString() shouldContain "/errors/authentication-required"
    }

    @Test
    fun `email uniqueness is case-insensitive`() {
        val email = uniqueEmail()
        register(email).statusCode shouldBe HttpStatus.CREATED

        val duplicate = register(email.uppercase())

        duplicate.statusCode shouldBe HttpStatus.CONFLICT
        duplicate.body!!["code"] shouldBe "EMAIL_ALREADY_REGISTERED"
    }

    @Test
    fun `a wrong password is refused without revealing whether the account exists`() {
        val email = uniqueEmail()
        register(email)

        val wrongPassword = login(email, "not-the-right-password")
        val noSuchAccount = login(uniqueEmail(), "not-the-right-password")

        wrongPassword.statusCode shouldBe HttpStatus.UNAUTHORIZED
        noSuchAccount.statusCode shouldBe HttpStatus.UNAUTHORIZED
        wrongPassword.body!!["code"] shouldBe "INVALID_CREDENTIALS"
        // Identical responses: nothing here distinguishes the two cases.
        wrongPassword.body!!["detail"] shouldBe noSuchAccount.body!!["detail"]
    }

    @Test
    fun `a short password is rejected with per-field detail`() {
        val response = rest.postForEntity(
            "/api/v1/auth/register",
            json(
                """
                {"email":"${uniqueEmail()}","password":"short","displayName":"Test"}
                """.trimIndent(),
            ),
            Map::class.java,
        )

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body!!["code"] shouldBe "VALIDATION_FAILED"
        (response.body!!["errors"] as Map<*, *>).keys shouldContain "password"
    }

    @Test
    fun `refreshing rotates the token and reusing the old one revokes the session`() {
        val registration = register(uniqueEmail())
        val firstRefresh = refreshCookieFrom(registration.headers)

        val rotated = refresh(firstRefresh)
        rotated.statusCode shouldBe HttpStatus.OK
        val secondRefresh = refreshCookieFrom(rotated.headers)
        secondRefresh shouldNotBe firstRefresh

        // Replaying the spent token is the leak signal.
        val replay = refresh(firstRefresh)
        replay.statusCode shouldBe HttpStatus.UNAUTHORIZED
        replay.body!!["code"] shouldBe "TOKEN_INVALID"

        // ...and it takes the whole family down, including the successor that
        // the legitimate client is holding.
        refresh(secondRefresh).statusCode shouldBe HttpStatus.UNAUTHORIZED
    }

    @Test
    fun `logout revokes the refresh token and clears the cookie`() {
        val registration = register(uniqueEmail())
        val refreshToken = refreshCookieFrom(registration.headers)

        val headers = HttpHeaders().apply {
            setBearerAuth(accessToken(registration.body!!))
            add(HttpHeaders.COOKIE, "plotted_refresh=$refreshToken")
        }
        val logout = rest.exchange("/api/v1/auth/logout", HttpMethod.POST, HttpEntity<Void>(headers), Void::class.java)

        logout.statusCode shouldBe HttpStatus.NO_CONTENT
        logout.headers[HttpHeaders.SET_COOKIE]!!.single() shouldContain "Max-Age=0"
        refresh(refreshToken).statusCode shouldBe HttpStatus.UNAUTHORIZED
    }

    @Test
    fun `default settings are created with the account and can be patched`() {
        val registration = register(uniqueEmail())
        val token = accessToken(registration.body!!)

        val defaults = get("/api/v1/users/me/settings", token)
        defaults.statusCode shouldBe HttpStatus.OK
        defaults.body!!["defaultAccessPolicy"] shouldBe "active_subscriptions_only"
        defaults.body!!["allowPaidRentals"] shouldBe false

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(token)
        }
        val patched = rest.exchange(
            "/api/v1/users/me/settings",
            HttpMethod.PATCH,
            HttpEntity("""{"maximumMonthlyBudget":45.00,"maximumActiveServices":2}""", headers),
            Map::class.java,
        )

        patched.statusCode shouldBe HttpStatus.OK
        patched.body!!["maximumActiveServices"] shouldBe 2
        // Unmentioned fields are left alone.
        patched.body!!["defaultAccessPolicy"] shouldBe "active_subscriptions_only"
    }

    @Test
    fun `one account's token does not read another account's data`() {
        val alice = register(uniqueEmail())
        val bob = register(uniqueEmail())

        val bobSeesBob = get("/api/v1/users/me", accessToken(bob.body!!))

        bobSeesBob.body!!["email"] shouldBe (bob.body!!["user"] as Map<*, *>)["email"]
        bobSeesBob.body!!["email"] shouldNotBe (alice.body!!["user"] as Map<*, *>)["email"]
    }

    // --- helpers -----------------------------------------------------------

    private fun register(email: String) = rest.postForEntity(
        "/api/v1/auth/register",
        json(
            """
                {"email":"$email","password":"a-sufficiently-long-passphrase","displayName":"Test User"}
            """.trimIndent(),
        ),
        Map::class.java,
    )

    private fun login(email: String, password: String) = rest.postForEntity(
        "/api/v1/auth/login",
        json("""{"email":"$email","password":"$password"}"""),
        Map::class.java,
    )

    private fun refresh(refreshToken: String) = rest.exchange(
        "/api/v1/auth/refresh",
        HttpMethod.POST,
        HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, "plotted_refresh=$refreshToken") }),
        Map::class.java,
    )

    private fun get(path: String, token: String) = rest.exchange(
        path,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(token) }),
        Map::class.java,
    )

    private fun json(body: String) = HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })

    @Suppress("UNCHECKED_CAST")
    private fun accessToken(body: Map<*, *>): String = body["accessToken"] as String

    private fun refreshCookieFrom(headers: HttpHeaders): String = headers[HttpHeaders.SET_COOKIE]!!
        .first { it.startsWith("plotted_refresh=") }
        .substringAfter("plotted_refresh=")
        .substringBefore(";")

    private fun uniqueEmail() = "user-${UUID.randomUUID()}@example.com"

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("plotted")
                .withUsername("plotted")
                .withPassword("plotted")
    }
}

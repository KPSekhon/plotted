package app.plotted.demo.api

import app.plotted.demo.domain.DemoService
import app.plotted.platform.security.RefreshCookie
import app.plotted.platform.spi.SessionIssuer
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/demo")
class DemoController(
    private val demo: DemoService,
    private val refreshCookie: RefreshCookie,
) {
    @PostMapping("/session")
    @SecurityRequirements
    @Operation(
        summary = "Start a demo session without signing up",
        description =
        "Creates a throwaway account with a watchlist and subscriptions built from the seeded " +
            "catalogue, and returns a normal session for it. The account expires and is swept. " +
            "Returns 404 when demo mode is not enabled on this deployment, and 429 when the " +
            "demo is at its account ceiling — this endpoint writes and is unauthenticated, so " +
            "it refuses rather than degrading.",
    )
    fun start(request: HttpServletRequest): ResponseEntity<DemoSessionResponse> {
        val started = demo.start(
            SessionIssuer.ClientContext(
                userAgent = request.getHeader(HttpHeaders.USER_AGENT),
                ipAddress = request.remoteAddr,
            ),
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(started.session.refreshToken).toString())
            .body(DemoSessionResponse.from(started))
    }
}

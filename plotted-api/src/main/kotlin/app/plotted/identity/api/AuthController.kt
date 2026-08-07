package app.plotted.identity.api

import app.plotted.identity.domain.IdentityService
import app.plotted.identity.domain.RefreshTokenService
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.PlottedAuthentication
import app.plotted.platform.security.RefreshCookie
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val identityService: IdentityService,
    private val refreshCookie: RefreshCookie,
) {
    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Create an account and start a session")
    fun register(@Valid @RequestBody request: RegisterRequest, httpRequest: HttpServletRequest): ResponseEntity<SessionResponse> {
        val session = identityService.register(
            IdentityService.RegisterCommand(
                email = request.email.trim(),
                password = request.password,
                displayName = request.displayName.trim(),
                regionCode = request.regionCode,
                timezone = request.timezone,
                client = clientContext(httpRequest),
            ),
        )
        return respondWithSession(session, HttpStatus.CREATED)
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange credentials for a session")
    fun login(@Valid @RequestBody request: LoginRequest, httpRequest: HttpServletRequest): ResponseEntity<SessionResponse> {
        val session = identityService.login(
            IdentityService.LoginCommand(
                email = request.email.trim(),
                password = request.password,
                client = clientContext(httpRequest),
            ),
        )
        return respondWithSession(session, HttpStatus.OK)
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
        summary = "Rotate the refresh token and issue a new access token",
        description =
        "Reads the HttpOnly refresh cookie. Presenting an already-spent token revokes " +
            "the entire token family, so a leaked token cannot be used quietly alongside " +
            "the legitimate session.",
    )
    fun refresh(
        @CookieValue(name = RefreshCookie.NAME, required = false) refreshToken: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<SessionResponse> {
        if (refreshToken.isNullOrBlank()) {
            throw ApiException(ErrorCode.TOKEN_INVALID, "No refresh token was presented")
        }
        val session = identityService.refresh(refreshToken, clientContext(httpRequest))
        return respondWithSession(session, HttpStatus.OK)
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session and revoke its refresh-token family")
    fun logout(@CookieValue(name = RefreshCookie.NAME, required = false) refreshToken: String?): ResponseEntity<Void> {
        val actor = (SecurityContextHolder.getContext().authentication as? PlottedAuthentication)?.user?.userId
        identityService.logout(refreshToken, actor)
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.cleared().toString())
            .build()
    }

    private fun respondWithSession(session: IdentityService.AuthenticatedSession, status: HttpStatus): ResponseEntity<SessionResponse> {
        val body = SessionResponse(
            accessToken = session.accessToken.token,
            tokenType = "Bearer",
            expiresIn = session.accessToken.expiresInSeconds,
            expiresAt = session.accessToken.expiresAt,
            user = UserResponse.from(session.account),
        )
        return ResponseEntity
            .status(status)
            .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(session.refreshToken).toString())
            .body(body)
    }

    private fun clientContext(request: HttpServletRequest) = RefreshTokenService.ClientContext(
        userAgent = request.getHeader(HttpHeaders.USER_AGENT),
        ipAddress = request.remoteAddr,
    )
}

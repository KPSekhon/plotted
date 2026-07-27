package app.plotted.platform.error

import org.springframework.http.HttpStatus

/**
 * Stable, machine-readable error codes. Clients branch on `code`, never on the
 * human-readable `detail`, which is free to change.
 */
enum class ErrorCode(
    val status: HttpStatus,
    val title: String,
) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token invalid"),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
    UNSUPPORTED_REGION(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported region"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    ;

    /** RFC 9457 `type` URI. Resolves to the published error reference. */
    val type: String get() = "https://plotted.app/errors/${name.lowercase().replace('_', '-')}"
}

/**
 * Base class for errors that map to a deliberate HTTP response. Anything not
 * derived from this becomes a 500 with no detail leaked to the client.
 */
open class ApiException(
    val code: ErrorCode,
    override val message: String,
    val errors: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundException(resource: String) : ApiException(ErrorCode.NOT_FOUND, "$resource was not found")

class InvalidCredentialsException :
    ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect")

class EmailAlreadyRegisteredException :
    ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account already exists for that email address")

class UnsupportedRegionException(region: String, supported: Set<String>) : ApiException(
    ErrorCode.UNSUPPORTED_REGION,
    "Region '$region' is not supported yet. Plotted currently supports: ${supported.sorted().joinToString(", ")}.",
)

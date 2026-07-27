package app.plotted.platform.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI
import java.time.Clock
import java.time.Instant

/**
 * Every error leaves the API as an RFC 9457 Problem Detail with a stable `code`
 * field for client branching (spec section 16).
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val clock: Clock,
) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        if (exception.code.status.is5xxServerError) {
            log.error("Request to {} failed: {}", request.requestURI, exception.message, exception)
        } else {
            log.debug("Request to {} rejected: {} ({})", request.requestURI, exception.message, exception.code)
        }
        val problem = problemDetail(exception.code, exception.message, request.requestURI)
        if (exception.errors.isNotEmpty()) {
            problem.setProperty("errors", exception.errors)
        }
        return ResponseEntity.status(exception.code.status).body(problem)
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(exception: AccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        // Deliberately vague: distinguishing "does not exist" from "not yours"
        // tells an attacker which resource identifiers are real.
        val problem = problemDetail(ErrorCode.FORBIDDEN, "You do not have access to this resource", request.requestURI)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.error("Unhandled exception for {}", request.requestURI, exception)
        val problem = problemDetail(
            ErrorCode.INTERNAL_ERROR,
            "The request could not be completed. The failure has been logged.",
            request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }

    /** Bean-validation failures, reported field by field. */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors = ex.bindingResult.fieldErrors.associate { field ->
            field.field to (field.defaultMessage ?: "is invalid")
        }
        val problem = problemDetail(
            ErrorCode.VALIDATION_FAILED,
            "One or more fields are invalid",
            request.getDescription(false).removePrefix("uri="),
        )
        problem.setProperty("errors", fieldErrors)
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status).body(problem)
    }

    private fun problemDetail(code: ErrorCode, detail: String, instance: String): ProblemDetail =
        ProblemDetail.forStatus(code.status).apply {
            this.type = URI.create(code.type)
            this.title = code.title
            this.detail = detail
            this.instance = URI.create(instance)
            setProperty("code", code.name)
            setProperty("timestamp", Instant.now(clock).toString())
        }
}

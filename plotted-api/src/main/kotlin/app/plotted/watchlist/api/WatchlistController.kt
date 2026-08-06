package app.plotted.watchlist.api

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.currentUser
import app.plotted.watchlist.domain.WatchStatus
import app.plotted.watchlist.domain.WatchlistService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * As with the identity endpoints, nothing here accepts a user or watchlist id.
 * The caller is taken from the security context and resolved to its own default
 * list, which removes "pass someone else's watchlist id" as a possibility rather
 * than defending against it in each method.
 */
@RestController
@RequestMapping("/api/v1/watchlist")
class WatchlistController(
    private val watchlist: WatchlistService,
) {
    @GetMapping
    @Operation(
        summary = "The signed-in user's watchlist",
        description = "Ordered by priority, highest first, then by when each title was added.",
    )
    fun list(): ResponseEntity<WatchlistResponse> = ResponseEntity.ok(WatchlistResponse.from(watchlist.list(currentUser().userId)))

    @PostMapping("/items")
    @Operation(
        summary = "Add a title to the watchlist",
        description =
        "Idempotent: adding a title that is already on the list returns the existing entry " +
            "rather than failing, because adding something twice is a slip rather than an error.",
    )
    fun add(@Valid @RequestBody request: AddWatchlistItemRequest): ResponseEntity<WatchlistItemResponse> {
        val entry = watchlist.add(
            userId = currentUser().userId,
            titleId = requireNotNull(request.titleId),
            priority = request.priority,
            desiredByDate = request.desiredByDate,
            notes = request.notes,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(WatchlistItemResponse.from(entry))
    }

    @PatchMapping("/items/{itemId}")
    @Operation(
        summary = "Update priority, status or intent for a watchlist entry",
        description = "Partial update; omitted fields are unchanged.",
    )
    fun update(
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateWatchlistItemRequest,
    ): ResponseEntity<WatchlistItemResponse> {
        // Parsed here rather than bound as an enum so an unknown value is a 400
        // naming the field, not a 400 about malformed JSON.
        val status = request.status?.let {
            WatchStatus.parse(it) ?: throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Unknown status '$it'",
                mapOf("status" to "Must be one of: ${WatchStatus.entries.joinToString(", ") { entry -> entry.dbValue }}"),
            )
        }

        val entry = watchlist.update(
            userId = currentUser().userId,
            itemId = itemId,
            patch = WatchlistService.WatchlistItemPatch(
                priority = request.priority,
                status = status,
                desiredByDate = request.desiredByDate,
                clearDesiredByDate = request.clearDesiredByDate,
                notes = request.notes,
                clearNotes = request.clearNotes,
            ),
        )
        return ResponseEntity.ok(WatchlistItemResponse.from(entry))
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove a title from the watchlist")
    fun remove(@PathVariable itemId: UUID): ResponseEntity<Void> {
        watchlist.remove(currentUser().userId, itemId)
        return ResponseEntity.noContent().build()
    }
}

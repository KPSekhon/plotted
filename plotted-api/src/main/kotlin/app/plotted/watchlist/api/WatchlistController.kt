package app.plotted.watchlist.api

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.currentUser
import app.plotted.watchlist.domain.SeriesProgressService
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
import org.springframework.web.bind.annotation.PutMapping
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
    private val seriesProgress: SeriesProgressService,
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

    @GetMapping("/blocked")
    @Operation(
        summary = "Titles the signed-in user has asked never to be recommended",
        description =
        "Most recently blocked first. This list is what makes blocking reversible: a preference " +
            "you can set and never see again is a one-way door.",
    )
    fun listBlocked(): ResponseEntity<BlockedTitlesResponse> =
        ResponseEntity.ok(BlockedTitlesResponse(watchlist.listBlocked(currentUser().userId).map(BlockedTitleResponse::from)))

    @PostMapping("/blocked")
    @Operation(
        summary = "Block a title from recommendations",
        description =
        "Suppresses the title in Tonight Mode and in the subscription optimiser. It does NOT hide " +
            "the title from catalogue search, and it does not remove it from the watchlist -- a " +
            "blocked watchlist entry is returned marked rather than deleted, so unblocking restores " +
            "the priority and notes intact. Idempotent: blocking twice returns the original block, " +
            "reason and timestamp included.",
    )
    fun block(@Valid @RequestBody request: BlockTitleRequest): ResponseEntity<BlockedTitleResponse> {
        val entry = watchlist.block(
            userId = currentUser().userId,
            titleId = requireNotNull(request.titleId),
            reason = request.reason,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(BlockedTitleResponse.from(entry))
    }

    @DeleteMapping("/blocked/{titleId}")
    @Operation(
        summary = "Stop blocking a title",
        description = "404 when the title was not blocked, so a client can tell an undo from a no-op.",
    )
    fun unblock(@PathVariable titleId: UUID): ResponseEntity<Void> {
        watchlist.unblock(currentUser().userId, titleId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/progress/{titleId}")
    @Operation(
        summary = "Where you are in a series, and what is next",
        description =
        "`next` is the first aired episode you have not finished. With nothing recorded that is " +
            "episode one, because not having started is different from having nothing left. " +
            "`next` is null only when there is genuinely nothing to watch: the series is finished, " +
            "or everything remaining has not aired.",
    )
    fun progress(@PathVariable titleId: UUID): ResponseEntity<SeriesProgressResponse> =
        ResponseEntity.ok(SeriesProgressResponse.from(seriesProgress.view(currentUser().userId, titleId)))

    @PutMapping("/progress/{titleId}")
    @Operation(
        summary = "Record the last episode you finished",
        description =
        "Replaces whatever was recorded, in either direction: correcting a mistake and starting a " +
            "rewatch both move backwards, and a marker that only goes forwards is one you cannot " +
            "fix. The position is checked against the catalogue, so a season or episode that does " +
            "not exist is a 400 rather than a row nothing can interpret. Specials (season 0) are " +
            "refused -- your place in the story is not the Christmas episode.",
    )
    fun recordProgress(
        @PathVariable titleId: UUID,
        @Valid @RequestBody request: RecordProgressRequest,
    ): ResponseEntity<SeriesProgressResponse> {
        val view = seriesProgress.record(
            userId = currentUser().userId,
            titleId = titleId,
            seasonNumber = requireNotNull(request.seasonNumber),
            episodeNumber = requireNotNull(request.episodeNumber),
        )
        return ResponseEntity.ok(SeriesProgressResponse.from(view))
    }

    @DeleteMapping("/progress/{titleId}")
    @Operation(
        summary = "Forget where you are in a series",
        description =
        "Idempotent, and returns the view rather than 204: clearing progress puts you back at " +
            "episode one, and the client needs to render that rather than an empty space.",
    )
    fun clearProgress(@PathVariable titleId: UUID): ResponseEntity<SeriesProgressResponse> =
        ResponseEntity.ok(SeriesProgressResponse.from(seriesProgress.clear(currentUser().userId, titleId)))
}

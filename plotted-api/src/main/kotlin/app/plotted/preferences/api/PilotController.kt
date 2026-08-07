package app.plotted.preferences.api

import app.plotted.platform.security.currentUser
import app.plotted.preferences.domain.PilotService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Pilot Season: fifteen "which of these two?" questions.
 *
 * As everywhere else, nothing here takes a user id — the caller comes from the
 * security context, so there is no id to tamper with.
 */
@RestController
@RequestMapping("/api/v1/pilot")
class PilotController(
    private val pilot: PilotService,
) {
    @GetMapping
    @Operation(
        summary = "The next question, and how far through the user is",
        description =
        "The ladder is deterministic given the catalogue, so abandoning the flow and returning " +
            "continues it rather than restarting on different questions.",
    )
    fun next(): ResponseEntity<PilotStateResponse> = ResponseEntity.ok(PilotStateResponse.from(pilot.next(currentUser().userId)))

    @PostMapping("/answers")
    @Operation(
        summary = "Answer a question, or skip it",
        description =
        "Omit chosenTitleId to skip. A skipped pair is recorded so it is not asked again and is " +
            "excluded from the fit: a forced choice between two unseen titles is a coin flip, and " +
            "recording one as a preference is worse than a shorter questionnaire. " +
            "Idempotent on the pair — answering the same comparison twice keeps the first answer, " +
            "because the fit counts rows and a duplicate would weight one opinion twice. " +
            "Returns the state that follows, so a client needs one request per answer rather than two.",
    )
    fun answer(@Valid @RequestBody request: PilotAnswerRequest): ResponseEntity<PilotStateResponse> {
        val state = pilot.answer(
            userId = currentUser().userId,
            leftTitleId = requireNotNull(request.leftTitleId),
            rightTitleId = requireNotNull(request.rightTitleId),
            chosenTitleId = request.chosenTitleId,
        )
        return ResponseEntity.ok(PilotStateResponse.from(state))
    }

    @GetMapping("/profile")
    @Operation(
        summary = "The fitted taste profile",
        description =
        "204 when nothing has been answered yet. A profile fitted from no answers is the " +
            "population's rather than yours, and returning it would report the prior as a finding.",
    )
    fun profile(): ResponseEntity<PreferenceProfileResponse> = pilot.profile(currentUser().userId)
        ?.let { ResponseEntity.ok(PreferenceProfileResponse.from(it)) }
        ?: ResponseEntity.noContent().build()

    @DeleteMapping("/answers")
    @Operation(
        summary = "Discard every answer and start again",
        description =
        "Deletes the comparisons rather than marking them superseded. Somebody who wants to redo " +
            "the questionnaire is saying the old answers were wrong, and keeping them to fit " +
            "against later would be disagreeing with them.",
    )
    fun reset(): ResponseEntity<Void> {
        pilot.reset(currentUser().userId)
        return ResponseEntity.noContent().build()
    }
}

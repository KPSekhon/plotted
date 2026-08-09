package app.plotted.preferences.domain

import app.plotted.platform.spi.TasteFixtures
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The demo persona, written down.
 *
 * ### Where these numbers come from
 *
 * They are not measured and they are not guesses dressed as measurements: they
 * are a reading of the curated demo watchlist in `demo/preferred-titles.txt`,
 * which a person chose. That list is mostly action anime and sitcoms with two
 * prestige films in it, so the persona leans light, fast, invented, and towards
 * series — and the answers this produces are simply what that lean implies,
 * question by question.
 *
 * The magnitudes are modest on purpose. A persona with huge weights answers
 * every question the same way and fits to a caricature; these are large enough
 * to be recovered from a handful of comparisons and small enough that the
 * fitter still reports honest uncertainty on the axes it saw least.
 *
 * **This is fixture data.** See [TasteFixtures] for what that forbids.
 */
@Component
class PilotTasteFixtures(
    private val pilot: PilotService,
) : TasteFixtures {

    override fun seedDemoPersona(userId: UUID, questions: Int): Int = pilot.seedPersona(userId, DEMO_PERSONA, questions)

    private companion object {
        /**
         * One weight per [TasteAxis], in declaration order.
         *
         * LEVITY, PACE, GROUNDED, COMMITMENT, RECENCY, ACCLAIM.
         */
        val DEMO_PERSONA = doubleArrayOf(
            // Lighter. The Office and Brooklyn Nine-Nine are half the list.
            0.6,
            // Faster. My Hero Academia, Chainsaw Man, The Boys, Fallout.
            0.8,
            // Invented rather than grounded — the list is anime and genre, and
            // has no documentary in it at all.
            -0.7,
            // A series is a commitment this persona is happy to make; nine of
            // the twelve titles are series.
            0.5,
            // Close to indifferent. The list spans 1994 to 2026, so recency is
            // the axis it says least about, and the fit should show that.
            0.1,
            // Well reviewed. Shawshank and Everything Everywhere All at Once
            // are both on there.
            0.4,
        )
    }
}

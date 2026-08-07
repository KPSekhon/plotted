package app.plotted.preferences.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What the profile is allowed to say.
 *
 * The fit always returns six numbers. The whole job of this layer is deciding
 * which of them are findings and which are the prior being reported back as a
 * discovery — and getting that wrong produces a profile page that is confident,
 * specific and made up.
 */
class PreferenceProfileTest {
    private val prior = BradleyTerry.Prior(DoubleArray(TasteAxis.size), precision = 1.0)

    @Test
    fun `an axis nobody was asked about is NOT_ASKED, not NO_PREFERENCE`() {
        // Every answer contrasts levity alone.
        val comparisons = List(12) { BradleyTerry.Comparison(axisDifference(TasteAxis.LEVITY, 1.0)) }
        val profile = PreferenceProfile.from(BradleyTerry.fit(comparisons, prior), prior)

        profile.opinionOf(TasteAxis.LEVITY).verdict shouldBe Verdict.LIKES
        // The distinction the whole class exists for. Both weights sit near the
        // prior mean; only the posterior width says one was measured and the
        // other was not.
        profile.opinionOf(TasteAxis.ACCLAIM).verdict shouldBe Verdict.NOT_ASKED
        profile.opinionOf(TasteAxis.GROUNDED).verdict shouldBe Verdict.NOT_ASKED
    }

    @Test
    fun `an axis asked about and answered both ways is NO_PREFERENCE`() {
        val balanced = List(8) { BradleyTerry.Comparison(axisDifference(TasteAxis.PACE, 1.0)) } +
            List(8) { BradleyTerry.Comparison(axisDifference(TasteAxis.PACE, -1.0)) }

        val profile = PreferenceProfile.from(BradleyTerry.fit(balanced, prior), prior)

        // A real finding, and a useful one: this axis can be ignored when
        // ranking for this person. Quite different from never having asked.
        profile.opinionOf(TasteAxis.PACE).verdict shouldBe Verdict.NO_PREFERENCE
    }

    @Test
    fun `a weak lean is not reported as a preference`() {
        // Three answers one way. Enough to move the weight, nowhere near enough
        // for the interval to clear zero.
        val weak = List(3) { BradleyTerry.Comparison(axisDifference(TasteAxis.RECENCY, 1.0)) }

        val profile = PreferenceProfile.from(BradleyTerry.fit(weak, prior), prior)
        val opinion = profile.opinionOf(TasteAxis.RECENCY)

        // The weight is positive and it is still not a finding. With fifteen
        // answers over six axes, an unfiltered profile reliably "discovers" two
        // or three preferences that are noise and look exactly like the real ones.
        (opinion.weight > 0.0) shouldBe true
        opinion.verdict.isDirectional shouldBe false
    }

    @Test
    fun `a strong consistent lean is reported, with a direction`() {
        val strong = List(14) { BradleyTerry.Comparison(axisDifference(TasteAxis.GROUNDED, -1.0)) }

        val profile = PreferenceProfile.from(BradleyTerry.fit(strong, prior), prior)
        val opinion = profile.opinionOf(TasteAxis.GROUNDED)

        opinion.verdict shouldBe Verdict.DISLIKES
        opinion.sentence shouldBe "You lean toward invented."
    }

    @Test
    fun `a profile with nothing to say does not score titles`() {
        val profile = PreferenceProfile.from(BradleyTerry.fit(emptyList(), prior), prior)

        profile.isInformative shouldBe false
        // Null rather than 0.5. Both rankers already handle an absent feature
        // properly; handing them a real-looking number computed from nothing
        // would put noise into a decision and call it signal.
        profile.match(TitleAttributes.NEUTRAL).shouldBeNull()
    }

    @Test
    fun `an informative profile scores an average title at one half`() {
        val comparisons = List(14) { BradleyTerry.Comparison(axisDifference(TasteAxis.LEVITY, 1.0)) }
        val profile = PreferenceProfile.from(BradleyTerry.fit(comparisons, prior), prior)

        profile.isInformative shouldBe true
        // The axes are centred by construction, so a title with no lean anywhere
        // must land exactly at even odds. Anything else means an axis picked up
        // a hidden offset.
        profile.match(TitleAttributes.NEUTRAL).shouldNotBeNull() shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `a title matching the stated preference outscores one opposing it`() {
        val likesLight = List(14) { BradleyTerry.Comparison(axisDifference(TasteAxis.LEVITY, 1.0)) }
        val profile = PreferenceProfile.from(BradleyTerry.fit(likesLight, prior), prior)

        val light = TitleAttributes(DoubleArray(TasteAxis.size).also { it[TasteAxis.LEVITY.ordinal] = 1.0 })
        val heavy = TitleAttributes(DoubleArray(TasteAxis.size).also { it[TasteAxis.LEVITY.ordinal] = -1.0 })

        val forLight = profile.match(light).shouldNotBeNull()
        val forHeavy = profile.match(heavy).shouldNotBeNull()

        (forLight > 0.5) shouldBe true
        (forHeavy < 0.5) shouldBe true
        // Symmetric axes, symmetric profile: the two must be mirror images, or
        // the scoring has an offset the centring was supposed to remove.
        (forLight + forHeavy) shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `stated opinions are ordered by strength, strongest first`() {
        val comparisons = List(16) { BradleyTerry.Comparison(axisDifference(TasteAxis.LEVITY, 1.0)) } +
            List(9) { BradleyTerry.Comparison(axisDifference(TasteAxis.PACE, 1.0)) }

        val profile = PreferenceProfile.from(BradleyTerry.fit(comparisons, prior), prior)

        // What the interface shows first should be what the data supports most.
        profile.stated.first().axis shouldBe TasteAxis.LEVITY
    }

    // --- helpers -----------------------------------------------------------

    private fun PreferenceProfile.opinionOf(axis: TasteAxis) = opinions.first { it.axis == axis }

    /** A comparison contrasting exactly one axis, so the expected outcome is knowable. */
    private fun axisDifference(axis: TasteAxis, magnitude: Double) = DoubleArray(TasteAxis.size).also { it[axis.ordinal] = magnitude }
}

/**
 * The ladder, which decides what can be learned at all.
 *
 * A profile can only be as good as its questions. These check the two properties
 * that make a question worth asking — real contrast on its own axis, and as
 * little confounding as the catalogue allows — plus the determinism that lets
 * somebody abandon the flow and come back to the same one.
 */
class PilotLadderTest {
    private val currentYear = 2026

    @Test
    fun `every pair contrasts the axis it was chosen for`() {
        val ladder = PilotLadder.build(catalogue(), questions = 12)

        ladder.forEach { pair ->
            val contrast = kotlin.math.abs(pair.left.attributes[pair.axis] - pair.right.attributes[pair.axis])
            // A pair that barely differs on its axis produces an answer driven
            // entirely by noise and records it as evidence.
            (contrast > 0.2) shouldBe true
        }
    }

    @Test
    fun `the ladder is the same every time`() {
        val first = PilotLadder.build(catalogue(), questions = 10)
        val second = PilotLadder.build(catalogue(), questions = 10)

        // Somebody who answers six questions, closes the tab and comes back must
        // not be restarted on a different ladder — their six answers were about
        // these pairs.
        first.map { it.left.titleId to it.right.titleId } shouldBe second.map { it.left.titleId to it.right.titleId }
    }

    @Test
    fun `no title dominates the ladder`() {
        val ladder = PilotLadder.build(catalogue(), questions = 12)

        val appearances = ladder.flatMap { listOf(it.left.titleId, it.right.titleId) }.groupingBy { it }.eachCount()

        // One strongly-polarised film otherwise wins every axis, and fifteen
        // questions about the same movie are both tedious and much weaker than
        // they look — the answers stop being independent.
        appearances.values.forEach { (it <= 3) shouldBe true }
    }

    @Test
    fun `axes are covered in rotation, so a half-finished ladder still spans them`() {
        val ladder = PilotLadder.build(catalogue(), questions = 6)

        // Filling one axis at a time would mean somebody who answers six of
        // fifteen knows everything about levity and nothing about anything else.
        ladder.map { it.axis }.distinct().size shouldBe ladder.size
    }

    @Test
    fun `the difference is taken from the chosen title, whichever side it was on`() {
        val ladder = PilotLadder.build(catalogue(), questions = 1)
        val pair = ladder.single()

        val choseLeft = pair.difference(pair.left.titleId)
        val choseRight = pair.difference(pair.right.titleId)

        // Sign convention is chosen-minus-rejected, always. Getting this
        // backwards produces a profile that is precisely inverted and looks fine.
        choseLeft.indices.forEach { choseLeft[it] shouldBe (-choseRight[it] plusOrMinus 1e-12) }
    }

    @Test
    fun `a catalogue too small for a question yields an empty ladder rather than a bad one`() {
        val identical = (0 until 4).map {
            PilotLadder.Choice(UUID.randomUUID(), "Same $it", TitleAttributes.NEUTRAL)
        }

        // Every pair has zero contrast on every axis. Asking anyway would record
        // coin flips as preferences.
        PilotLadder.build(identical, questions = 10) shouldBe emptyList()
    }

    private fun catalogue(): List<PilotLadder.Choice> = listOf(
        choice("a1b2c3d4-0000-0000-0000-000000000001", "Light fast recent", setOf("Comedy", "Action"), false, 2025, 7.8),
        choice("a1b2c3d4-0000-0000-0000-000000000002", "Heavy slow old", setOf("Drama", "History"), false, 2005, 7.2),
        choice("a1b2c3d4-0000-0000-0000-000000000003", "Invented", setOf("Fantasy", "Science Fiction"), false, 2022, 6.5),
        choice("a1b2c3d4-0000-0000-0000-000000000004", "Documentary", setOf("Documentary"), false, 2024, 8.4),
        choice("a1b2c3d4-0000-0000-0000-000000000005", "Light series", setOf("Comedy"), true, 2024, 7.9),
        choice("a1b2c3d4-0000-0000-0000-000000000006", "Heavy series", setOf("Drama", "Crime"), true, 2023, 8.1),
        choice("a1b2c3d4-0000-0000-0000-000000000007", "Old animation", setOf("Animation", "Family"), false, 2001, 7.0),
        choice("a1b2c3d4-0000-0000-0000-000000000008", "Recent thriller", setOf("Thriller"), false, 2026, 6.1),
        choice("a1b2c3d4-0000-0000-0000-000000000009", "War film", setOf("War", "Drama"), false, 2019, 7.5),
        choice("a1b2c3d4-0000-0000-0000-00000000000a", "Sci-fi series", setOf("Science Fiction"), true, 2025, 7.4),
    )

    private fun choice(id: String, name: String, genres: Set<String>, series: Boolean, year: Int, rating: Double) = PilotLadder.Choice(
        UUID.fromString(id),
        name,
        TitleAttributes.of(genres, series, year, rating, currentYear),
    )
}

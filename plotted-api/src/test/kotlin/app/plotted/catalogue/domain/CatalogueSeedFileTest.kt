package app.plotted.catalogue.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The seed file itself, checked without a database or a TMDB token.
 *
 * The seed is 519 lines that nobody reads end to end, and a single malformed one
 * costs a wasted TMDB lookup and a title missing from the catalogue -- which
 * shows up later as a coverage number that is quietly wrong rather than as an
 * error. Parsing it is cheap and this is the only place it gets exercised
 * before a real seed run, which needs Postgres and a token and therefore
 * happens approximately never on a developer machine.
 */
class CatalogueSeedFileTest {
    private val lines: List<String> = requireNotNull(
        javaClass.classLoader.getResourceAsStream("seed/canadian-seed.txt"),
    ) { "The seed file is missing from the classpath" }
        .bufferedReader()
        .readLines()

    private val entries: List<String> = lines
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }

    @Test
    fun `every derived line is a well-formed tmdb reference`() {
        val malformed = entries
            .filter { it.startsWith("tmdb:") }
            .filterNot { it.matches(Regex("""tmdb:\d+:(movie|tv|series)""")) }

        // A malformed one would be searched as a name -- "tmdb:634649:movie" is
        // not a film, so TMDB would return something arbitrary and the seed would
        // quietly contain the wrong title.
        malformed.shouldBeEmpty()
    }

    @Test
    fun `no tmdb id is listed twice`() {
        val ids = entries.filter { it.startsWith("tmdb:") }.map { it.split(':')[1] }

        // Ingestion is idempotent so a duplicate is not corrupting, but it is a
        // wasted round trip and it makes the line count a lie about how many
        // titles the seed actually contains.
        (ids.size - ids.toSet().size) shouldBe 0
    }

    @Test
    fun `the curated names survived the rebuild`() {
        val names = entries.filterNot { it.startsWith("tmdb:") }

        // The generator rewrites the header and the derived section and appends
        // the curated block verbatim. If a future change to it ever drops that
        // block, the seed silently loses the awkward cases that keep the coverage
        // numbers informative -- and nothing else would notice.
        names.size shouldBeGreaterThan 100
        names.contains("Schitt's Creek") shouldBe true
    }

    @Test
    fun `the file is big enough to be the seed section 7-3 asks for`() {
        // Roughly 500. Asserted as a floor rather than an exact number because
        // the curated half is meant to grow by hand, and a test that fails when
        // somebody adds a title they like is a test that gets deleted.
        entries.size shouldBeGreaterThan 450
    }

    @Test
    fun `films and series are both well represented`() {
        val derived = entries.filter { it.startsWith("tmdb:") }
        val films = derived.count { it.endsWith(":movie") }
        val series = derived.size - films

        // The allocation is an even split, and it matters: series are where the
        // runtime work and the "is this a commitment" question actually bite, so
        // a seed skewed to film would leave both under-exercised.
        films shouldBeGreaterThan 150
        series shouldBeGreaterThan 150
    }
}

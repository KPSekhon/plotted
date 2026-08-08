package app.plotted.catalogue.domain

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

/**
 * The transaction boundary that carries the availability pipeline.
 *
 * Testing for an annotation is usually weak. Here the annotation *is* the
 * mechanism, and its absence is not a degraded behaviour but a silent, total
 * failure: `AvailabilityIngestionService` listens with
 * `@TransactionalEventListener`, which discards events published outside a
 * transaction. Without this annotation every ingest stores a title and drops its
 * availability fetch, with no exception and no log line. That was true for the
 * life of the project and produced 503 titles with zero availability rows.
 *
 * The two properties below are the ones that were wrong, and both are cheap to
 * assert:
 *
 *  * the write and the publish are inside a transaction at all, and
 *  * that transaction is on a bean the caller reaches **through a proxy**.
 *
 * The second is why `TitleWriter` exists rather than an annotation on
 * `TitleIngestionService.ingest`: `ingestWithSeasons` and `ingestAll` call
 * `ingest` on `this`, so a `@Transactional` there would apply to neither, and
 * series and batches would keep failing while single ingests appeared fixed.
 *
 * This runs without Docker, which matters: everything that would catch the bug
 * at runtime needs a database, and a third of this suite cannot run here.
 */
class TitleWriterTransactionTest {
    @Test
    fun `storing a title is transactional`() {
        val store = TitleWriter::class.java.methods.single { it.name == "store" }

        // Not "the class is annotated" -- the method, because that is what the
        // proxy advises.
        store.getAnnotation(Transactional::class.java).shouldNotBeNull()
    }

    @Test
    fun `the transaction does not live on the service that calls itself`() {
        val selfCalling = TitleIngestionService::class.java.methods
            .filter { it.getAnnotation(Transactional::class.java) != null }
            .map { it.name }

        // `ingest` is called from `ingestWithSeasons` and `ingestAll` on `this`.
        // An annotation here would be read, look applied, and do nothing for
        // either caller -- the failure mode that hid this bug in the first place,
        // and the one a well-meaning fix would reintroduce.
        selfCalling shouldBe emptyList()
    }
}

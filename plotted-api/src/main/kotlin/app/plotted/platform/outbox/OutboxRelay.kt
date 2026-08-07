package app.plotted.platform.outbox

import app.plotted.platform.persistence.OutboxRelayRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Drains the transactional outbox.
 *
 * The table and its writer have existed since phase 1; this is the half that
 * makes them do anything. A domain change writes its event in the same
 * transaction as the change itself, and this reads them out afterwards, so the
 * two can never disagree about whether something happened.
 *
 * ### At-least-once, said out loud
 *
 * An event is marked published *after* its handler returns, so a crash in
 * between redelivers it. The other order turns the same crash into silent loss,
 * and a lost notification looks exactly like a system with nothing to say.
 * [OutboxHandler] carries the idempotency requirement that choice creates.
 *
 * ### One event's failure is not the batch's
 *
 * Each event is settled in its own transaction, in [OutboxDispatcher]. Wrapping
 * a batch in one transaction would mean a single poisoned event rolled back the
 * successful deliveries beside it, then redelivered all of them next tick.
 */
@Component
@ConditionalOnProperty(
    prefix = "plotted.outbox.relay",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxRelay(
    private val relay: OutboxRelayRepository,
    private val dispatcher: OutboxDispatcher,
    private val properties: OutboxRelayProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${plotted.outbox.relay.poll-interval-ms:5000}")
    fun poll(): Report = drain()

    fun drain(): Report {
        var delivered = 0
        var failed = 0

        // Bounded rather than "until empty". A relay that keeps draining while
        // work keeps arriving never yields, and the next tick is cheap.
        repeat(properties.maxBatchesPerPoll) {
            val batch = relay.claim(properties.batchSize, properties.leaseDuration)
            if (batch.isEmpty()) return@repeat

            batch.forEach { record ->
                if (dispatcher.deliver(record)) delivered++ else failed++
            }
        }

        if (delivered > 0 || failed > 0) {
            log.info("Outbox relay: {} delivered, {} failed", delivered, failed)
        }
        return Report(delivered, failed, relay.stuckCount(properties.stuckAfterAttempts))
    }

    data class Report(val delivered: Int, val failed: Int, val stuck: Int)
}

@ConfigurationProperties(prefix = "plotted.outbox.relay")
data class OutboxRelayProperties(
    val enabled: Boolean = true,
    val pollIntervalMs: Long = 5_000,
    /** Rows per claim. Small enough that a slow handler does not sit on a whole tick's work. */
    val batchSize: Int = 50,
    val maxBatchesPerPoll: Int = 10,
    /**
     * How long a claimed event stays invisible to other workers.
     *
     * The ceiling on how long one event may take to handle before a second
     * worker may pick it up alongside the first. Long enough to cover a slow
     * handler, short enough that a worker killed mid-batch does not strand its
     * events for long.
     */
    val leaseDuration: Duration = Duration.ofMinutes(2),
    val baseBackoff: Duration = Duration.ofSeconds(10),
    val maximumBackoff: Duration = Duration.ofMinutes(15),
    /**
     * Attempts after which an event is reported as stuck.
     *
     * It keeps being retried at the capped interval rather than being parked. The
     * usual cause is a downstream outage that ends, and an event that stopped
     * retrying would need a person to notice and requeue it. What changes here is
     * that it starts being counted.
     */
    val stuckAfterAttempts: Int = 6,
)

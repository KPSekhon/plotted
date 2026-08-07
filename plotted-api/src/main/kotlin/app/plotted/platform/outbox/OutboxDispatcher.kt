package app.plotted.platform.outbox

import app.plotted.platform.persistence.OutboxRelayRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.math.min

/**
 * Delivers one outbox event and settles it.
 *
 * A separate bean from [OutboxRelay], which is not tidiness. Spring's
 * `@Transactional` works through a proxy, so a method calling an annotated
 * method *on itself* gets no transaction at all -- the annotation is read,
 * looks applied, and does nothing. Keeping the per-event transaction in another
 * bean means the call crosses the proxy and the boundary is real.
 *
 * That failure is worth being specific about because this codebase has already
 * shipped its sibling: phase 1's refresh-token reuse detection revoked a token
 * family and then threw, and the throw rolled the revocation back. The 401 still
 * came out, so nothing looked wrong, while the stolen token kept working.
 */
@Component
class OutboxDispatcher(
    private val relay: OutboxRelayRepository,
    private val properties: OutboxRelayProperties,
    handlers: List<OutboxHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Handlers by event type, resolved once at startup.
     *
     * Two handlers claiming one type would otherwise show up as events delivered
     * to whichever bean the classpath happened to order first. This fails at
     * construction instead, before anything is delivered at all.
     */
    private val byEventType: Map<String, OutboxHandler> = handlers
        .groupBy { it.eventType }
        .mapValues { (eventType, candidates) ->
            require(candidates.size == 1) {
                "More than one OutboxHandler declares eventType '$eventType': " +
                    candidates.joinToString { it::class.java.name }
            }
            candidates.single()
        }

    /**
     * Runs the handler, then records what happened.
     *
     * [Propagation.REQUIRES_NEW] so the settle survives the failure it is
     * recording. Written into a transaction the handler's exception had already
     * marked rollback-only, the attempt count and the error message would be
     * discarded with the work -- and the event would come back looking untried,
     * on every tick, forever.
     *
     * Published is set *after* the handler returns. A crash in between redelivers;
     * the other order loses the work silently, and a lost notification is
     * indistinguishable from a system with nothing to say.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deliver(record: OutboxRecord): Boolean {
        val handler = byEventType[record.eventType]
        if (handler == null) {
            // Not marked published. An event nobody handles is a gap, and
            // publishing it would file that gap as a success: the row would be
            // gone and the work would never have happened.
            relay.markFailed(
                record.id,
                "No handler registered for event type '${record.eventType}'",
                backoffFor(record.attempts + 1),
            )
            return false
        }

        return try {
            handler.handle(record)
            relay.markPublished(record.id)
            true
        } catch (failure: Exception) {
            log.warn(
                "Outbox event {} ({}) failed on attempt {}: {}",
                record.id,
                record.eventType,
                record.attempts + 1,
                failure.message,
            )
            relay.markFailed(record.id, failure.describe(), backoffFor(record.attempts + 1))
            false
        }
    }

    /**
     * Exponential, capped.
     *
     * Capped because unbounded doubling reaches an interval measured in weeks,
     * which behaves like having given up without saying so. The cap keeps a
     * recovered downstream picked up within minutes;
     * [OutboxRelayProperties.stuckAfterAttempts] is what makes a persistent
     * failure visible.
     */
    internal fun backoffFor(attempts: Int): Duration {
        val exponent = min(attempts - 1, MAX_DOUBLINGS)
        val seconds = properties.baseBackoff.seconds shl exponent
        return Duration.ofSeconds(min(seconds, properties.maximumBackoff.seconds))
    }

    /** Type as well as message, because a bare `NullPointerException` has a null one. */
    private fun Exception.describe(): String = "${this::class.java.simpleName}: ${message ?: "no message"}"

    private companion object {
        /** Past this the shift itself is the hazard, and the cap has long since applied. */
        const val MAX_DOUBLINGS = 10
    }
}

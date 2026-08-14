package app.plotted.solver

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlin.system.exitProcess

/**
 * The optimiser worker: one request in on stdin, one answer out on stdout, exit.
 *
 * ### Why the optimiser has its own process at all
 *
 * OR-Tools reaches CP-SAT through JNI, and a native fault is not an exception —
 * it is a `SIGSEGV` or an `EXCEPTION_ACCESS_VIOLATION` that takes the JVM down
 * with it. Nothing in Kotlin catches that. On the Windows development machine
 * one request to `/api/v1/plan` reliably killed the whole API, and every other
 * endpoint with it, so a solver problem presented as an outage.
 *
 * A child process makes that survivable. When the natives die, what dies is one
 * solve: the parent sees a non-zero exit status and answers the request with an
 * error, which is the right shape for "the optimiser is unavailable" and was not
 * reachable before.
 *
 * ### Why not a long-lived worker
 *
 * A pool would amortise JVM startup, which is the obvious cost here. Rejected
 * for now: a crashed pool member has to be noticed, drained and replaced, and
 * getting that wrong reintroduces exactly the failure this removes — while the
 * endpoint's own budget is already twenty seconds, against which a JVM start is
 * small. A process per solve has no state to corrupt.
 *
 * ### The exit codes are part of the protocol
 *
 * The parent tells a refusal from a crash by status, so these matter as much as
 * the JSON:
 *
 * - `0` — an answer was written to stdout. It may be `Infeasible`, which is an
 *   answer rather than a failure.
 * - `2` — the request could not be read. A caller bug, not a solver one.
 * - anything else, or no exit at all — the process died. That is the case this
 *   design exists to contain, and the parent reports it as the optimiser being
 *   unavailable rather than as a wrong plan.
 *
 * Diagnostics go to stderr without exception, so stdout carries exactly one JSON
 * document and a stray line can never be parsed as an answer.
 */
internal val workerMapper: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    // A field one side knows and the other does not is a protocol mismatch worth
    // failing on, rather than skipping past to solve a subtly different problem.
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

const val EXIT_UNREADABLE_REQUEST = 2

fun main() {
    val request = try {
        workerMapper.readValue(System.`in`.readBytes(), PlanRequest::class.java)
    } catch (failure: Exception) {
        System.err.println("plotted-solver: could not read the request: ${failure.message}")
        exitProcess(EXIT_UNREADABLE_REQUEST)
    }

    val outcome = PlanModel().solve(request)

    // Written as bytes and flushed explicitly. An answer lost to a buffer at exit
    // is indistinguishable, from the parent's side, from the crash this process
    // exists to survive — and would be reported as one.
    System.out.write(workerMapper.writeValueAsBytes(outcome))
    System.out.flush()
}

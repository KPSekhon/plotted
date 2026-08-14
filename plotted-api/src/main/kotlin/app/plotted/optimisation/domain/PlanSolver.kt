package app.plotted.optimisation.domain

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.solver.PlanOutcome
import app.plotted.solver.PlanRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the optimiser, in a JVM that is not this one.
 *
 * ### Why
 *
 * CP-SAT is reached through JNI, and a native fault is a process death rather
 * than an exception — nothing here can catch it. While the model ran in-process,
 * one request to `/api/v1/plan` on a machine with a bad OR-Tools install took
 * the entire API with it: every other endpoint, every in-flight request, the
 * scheduled jobs. A solver problem presented as an outage, and the only evidence
 * was an `hs_err_pid*.log` left in the working directory.
 *
 * The model now lives in `plotted-solver` and runs as a child process. The
 * blast radius of a native crash is that child. This class is the parent half:
 * it writes a request to the worker's stdin, reads an answer from its stdout,
 * and turns every way that can fail into [OptimiserUnavailable] — which the
 * controller renders as a 503, because a request worth retrying and a wrong
 * answer are different things.
 *
 * ### What this deliberately does not do
 *
 * It does not interpret the plan. Coverage, the objective, the sensitivity
 * report and `PlanChecker`'s audit are all computed inside the worker from the
 * same code as before, and arrive whole. Re-deriving any of it here would mean
 * two implementations of the same claim, which is the thing `PlanChecker` exists
 * to avoid rather than to demonstrate.
 *
 * It also does not retry. A crash that is going to happen will happen again, and
 * a second five-second solve inside a twenty-second budget buys a slower failure.
 */
@Component
class PlanSolver(
    private val properties: OptimiserProperties,
    private val objectMapper: ObjectMapper,
    /** Optional so a missing metrics backend can never be why the optimiser fails to start. */
    private val meters: MeterRegistry = SimpleMeterRegistry(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun solve(request: PlanRequest): PlanOutcome {
        val startedAt = System.nanoTime()
        return try {
            run(request)
        } finally {
            meters.timer("plotted.optimiser.solve").record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
        }
    }

    private fun run(request: PlanRequest): PlanOutcome {
        val process = try {
            ProcessBuilder(command())
                // Inherited, so the worker's diagnostics land in the API's own
                // log rather than in a buffer nobody reads.
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        } catch (failure: Exception) {
            throw OptimiserUnavailable("the optimiser worker could not be started", failure)
        }

        val answer = try {
            process.outputStream.use { it.write(objectMapper.writeValueAsBytes(request)) }
            // Read before waiting. A worker that fills the pipe buffer while the
            // parent waits for exit deadlocks, and it would look exactly like a
            // slow solve.
            val bytes = process.inputStream.use { it.readBytes() }

            if (!process.waitFor(properties.timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw OptimiserUnavailable("the optimiser did not answer within ${properties.timeout}")
            }
            bytes
        } catch (failure: OptimiserUnavailable) {
            throw failure
        } catch (failure: Exception) {
            process.destroyForcibly()
            throw OptimiserUnavailable("the optimiser worker could not be reached", failure)
        }

        val status = process.exitValue()
        if (status != 0) {
            // The case this whole design is for, and the order matters: status is
            // checked *before* anything is parsed. A JVM dying on a native fault
            // writes its `EXCEPTION_ACCESS_VIOLATION` report to **stdout**, not
            // stderr -- so on a crash the bytes read above are a hs_err dump, and
            // trying to read them as an answer first would report a JSON parse
            // error for what is actually a dead solver.
            logger.error("Optimiser worker exited with status {}; see its stderr above", status)
            throw OptimiserUnavailable("the optimiser stopped unexpectedly (exit $status)")
        }
        if (answer.isEmpty()) {
            logger.error("Optimiser worker exited cleanly but wrote no answer")
            throw OptimiserUnavailable("the optimiser produced no answer")
        }

        return try {
            objectMapper.readValue(answer, PlanOutcome::class.java)
        } catch (failure: Exception) {
            // Answered, but not in a shape this side understands. Reported as
            // unavailable rather than guessed at: a half-parsed plan is financial
            // advice assembled from a misunderstanding.
            logger.error("Optimiser worker returned an answer this version cannot read", failure)
            throw OptimiserUnavailable("the optimiser returned an unreadable answer", failure)
        }
    }

    /**
     * `java -cp <solver jar and its dependencies> app.plotted.solver.WorkerKt`.
     *
     * The JVM is the one running this process, found through [ProcessHandle], so
     * the worker cannot end up on a different Java than the API was launched
     * with. The classpath is a directory rather than a list, because OR-Tools
     * unpacks its natives from a jar on the classpath and repackaging it into a
     * fat jar is the arrangement most likely to break that.
     */
    private fun command(): List<String> {
        val directory = File(properties.workerDirectory)
        val jar = File(directory, WORKER_JAR)
        if (!jar.isFile) {
            throw OptimiserUnavailable(
                "the optimiser worker is not built: expected ${jar.absolutePath}. " +
                    "Run `./gradlew :plotted-solver:build`, or set plotted.optimiser.worker-directory.",
            )
        }
        val classpath = listOf(jar.absolutePath, File(directory, "dependencies").absolutePath + File.separator + "*")
            .joinToString(File.pathSeparator)
        return listOf(javaBinary(), "-cp", classpath, WORKER_MAIN)
    }

    private fun javaBinary(): String = ProcessHandle.current().info().command().orElse(
        File(File(System.getProperty("java.home"), "bin"), "java").absolutePath,
    )

    private companion object {
        const val WORKER_JAR = "plotted-solver.jar"
        const val WORKER_MAIN = "app.plotted.solver.WorkerKt"
    }
}

/**
 * The optimiser could not be run, so there is no plan and no diagnosis either.
 *
 * Deliberately not a [PlanOutcome]. `Infeasible` and `NothingToPlan` are
 * *answers* — the model ran and this is what it found — and folding "the solver
 * died" in beside them would let an infrastructure failure render as a finding
 * about the user's subscriptions.
 */
class OptimiserUnavailable(message: String, cause: Throwable? = null) :
    ApiException(ErrorCode.OPTIMISER_UNAVAILABLE, "Cancel Culture could not be run: $message.", cause = cause)

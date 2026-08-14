package app.plotted.optimisation.domain

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Where the optimiser worker lives, and how long to wait for it.
 *
 * @param workerDirectory Holds `plotted-solver.jar` and a `dependencies/`
 *   directory beside it, which is what `:plotted-solver:build` produces. The
 *   default is the Gradle output path so a developer who has built the project
 *   needs no configuration; the Docker image sets it to where it copied them.
 * @param timeout How long the worker may take before it is killed. Generous
 *   against the solver's own five-second cap times the four solves one request
 *   can need, because the point of this bound is to catch a *hung* process
 *   rather than to second-guess a slow one -- a limit tight enough to interrupt
 *   real solves would turn a working optimiser into an intermittent one.
 */
@ConfigurationProperties(prefix = "plotted.optimiser")
data class OptimiserProperties(
    val workerDirectory: String = "plotted-solver/build/libs",
    val timeout: Duration = Duration.ofSeconds(30),
)

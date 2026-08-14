package app.plotted.solver

/**
 * Whether it is safe to run CP-SAT in this JVM.
 *
 * The same shape as [DockerSupport]: a capability the dev machine may not have,
 * checked once so the tests that need it skip locally and run in CI.
 *
 * ### Why this cannot just try it and catch the failure
 *
 * OR-Tools is a JNI binding, and on the current Windows dev machine
 * `jniortools.dll` fails to resolve a dependency (`error code 126`) and the
 * solve then dies with an `EXCEPTION_ACCESS_VIOLATION` inside `msvcp140.dll`.
 * That is a process crash, not an exception — there is nothing to catch, and
 * the test JVM disappears mid-run taking the whole Gradle worker with it.
 *
 * The cause is not established. An outdated Visual C++ redistributable was the
 * obvious suspect and was ruled out: the installed runtimes are current. See
 * the phase 5 section of `docs/PROGRESS.md`.
 *
 * So the check has to be made *before* touching native code, which means it can
 * only be a guess about the environment rather than a probe of it. The guess is
 * deliberately conservative: assume Windows cannot until told otherwise.
 *
 * **On Windows,** set `PLOTTED_SOLVER_ENABLED=true` once the crash is
 * understood and fixed. On Linux and macOS, and therefore in CI and in
 * production, the solver runs unconditionally.
 */
object SolverSupport {
    @JvmStatic
    fun isSolverAvailable(): Boolean {
        if (System.getenv("PLOTTED_SOLVER_ENABLED")?.equals("true", ignoreCase = true) == true) return true
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return !os.contains("win")
    }
}

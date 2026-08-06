package app.plotted.support

/**
 * Whether it is safe to run CP-SAT in this JVM.
 *
 * The same shape as [DockerSupport]: a capability the dev machine may not have,
 * checked once so the tests that need it skip locally and run in CI.
 *
 * ### Why this cannot just try it and catch the failure
 *
 * OR-Tools is a JNI binding. On a Windows box whose Visual C++ redistributable
 * is older than the one the natives were built against, `jniortools.dll` fails
 * to load its dependencies (`error code 126`), and the subsequent solve dies
 * with an `EXCEPTION_ACCESS_VIOLATION` inside `msvcp140.dll`. That is a process
 * crash, not an exception — there is nothing to catch, and the test JVM
 * disappears mid-run taking the whole Gradle worker with it.
 *
 * So the check has to be made *before* touching native code, which means it can
 * only be a guess about the environment rather than a probe of it. The guess is
 * deliberately conservative: assume Windows cannot until told otherwise.
 *
 * **Fixing it on Windows:** install the current Microsoft Visual C++
 * Redistributable (x64), then set `PLOTTED_SOLVER_ENABLED=true`. On Linux and
 * macOS, and therefore in CI and in production, the solver runs unconditionally.
 */
object SolverSupport {
    @JvmStatic
    fun isSolverAvailable(): Boolean {
        if (System.getenv("PLOTTED_SOLVER_ENABLED")?.equals("true", ignoreCase = true) == true) return true
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return !os.contains("win")
    }
}

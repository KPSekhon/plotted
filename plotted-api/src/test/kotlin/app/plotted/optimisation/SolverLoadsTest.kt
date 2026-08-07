package app.plotted.optimisation

import com.google.ortools.Loader
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

/**
 * The native library loads and CP-SAT solves.
 *
 * OR-Tools is a JNI binding, so "the dependency resolved" and "the solver runs"
 * are different claims, and only the second one matters. Gated on
 * [app.plotted.support.SolverSupport] because on a Windows box with an outdated
 * Visual C++ redistributable the solve does not throw, it kills the JVM.
 */
@EnabledIf("app.plotted.support.SolverSupport#isSolverAvailable")
class SolverLoadsTest {
    @Test
    fun `cp-sat solves a trivial model`() {
        Loader.loadNativeLibraries()
        val model = CpModel()
        val x = model.newBoolVar("x")
        model.addEquality(x, 1)
        val solver = CpSolver()
        solver.solve(model) shouldBe CpSolverStatus.OPTIMAL
    }
}

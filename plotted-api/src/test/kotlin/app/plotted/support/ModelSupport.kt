package app.plotted.support

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether a trained model and its golden vectors are on disk.
 *
 * The same shape as [DockerSupport] and [app.plotted.support.SolverSupport]: a
 * capability a checkout may not have, checked once so the tests that need it
 * skip rather than fail.
 *
 * Both artefacts are committed, so in practice this is always true and the gate
 * is insurance rather than routine — a checkout that excludes `models/`, or a
 * shallow clone without LFS if that is ever introduced, should skip these tests
 * rather than report the model broken.
 *
 * Unlike the solver gate, this one can safely *probe* instead of guessing:
 * missing files are an ordinary absence rather than something that takes the
 * JVM down with it.
 */
object ModelSupport {
    private val MODEL: Path = Path.of("..", "models", "ranker.onnx")
    private val GOLDEN: Path = Path.of("..", "models", "golden-vectors.json")

    @JvmStatic
    fun isModelAvailable(): Boolean = Files.isRegularFile(MODEL) && Files.isRegularFile(GOLDEN)

    fun modelPath(): Path = MODEL

    fun goldenPath(): Path = GOLDEN
}

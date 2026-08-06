package app.plotted.recommendation.model

import app.plotted.recommendation.domain.Ranker
import app.plotted.recommendation.evaluation.MetadataCensoringSimulation
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a training set using the **serving** feature extractor.
 *
 *     ./gradlew :plotted-api:exportTrainingData
 *
 * ### Why training data is produced by the serving code
 *
 * The usual arrangement is the other way round: features are computed in Python
 * for training and reimplemented in the serving language for inference. That
 * arrangement is the single largest source of training-serving skew in applied
 * machine learning, because the two implementations start identical and then
 * drift — a bug fixed on one side, a unit change, a different missing-value
 * convention. Nothing throws; the model simply gets worse.
 *
 * Here, [FeatureSchema] is the only implementation that exists. The training
 * script never computes a feature; it reads columns this produced. Skew is not
 * guarded against, it is *unrepresentable* — and what remains guardable, the
 * export step itself, is what the golden vectors check.
 *
 * ### What the label is, and what it is not
 *
 * The target is the linear ranker's own score, so the model this trains is a
 * **distillation of the shipped scorer** rather than a better recommender. It
 * cannot be otherwise: nobody has used Plotted, so there are no outcomes to
 * learn from. See `docs/MODEL.md`.
 *
 * That makes it a genuinely useful fixture rather than a decorative one. A
 * distilled model should reproduce the linear ranking closely, so the whole
 * export → train → convert → load → score path becomes **falsifiable end to end
 * with no real data**: if the ONNX model disagrees with the linear ranker, the
 * pipeline is broken, and that is exactly what `GoldenVectorTest` and the
 * evaluation harness assert.
 */
object TrainingDataExport {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = Path.of(args.firstOrNull() ?: DEFAULT_OUTPUT)
        Files.createDirectories(output.parent)

        val ranker = Ranker()
        // The same generator phase 7 evaluates against, so the training
        // distribution and the evaluation distribution cannot drift apart.
        val queries = MetadataCensoringSimulation(queries = QUERIES, seed = SEED).generate()

        var rows = 0
        Files.newBufferedWriter(output).use { writer ->
            writer.write((FeatureSchema.names + LABEL_COLUMN).joinToString(","))
            writer.newLine()

            queries.forEach { query ->
                query.candidates.forEach { candidate ->
                    val label = ranker.score(candidate, query.context, query.subscribedProviderIds, query.askedOn)?.score
                        // A candidate the linear ranker cannot score has no
                        // target. Writing 0 would teach the model that unknown
                        // means worthless, which is the exact mistake the whole
                        // missing-value design avoids.
                        ?: return@forEach
                    val vector = FeatureSchema.extract(candidate, query.context, query.subscribedProviderIds, query.askedOn)
                    writer.write(vector.joinToString(",") { formatFeature(it) })
                    writer.write(",")
                    writer.write(label.toString())
                    writer.newLine()
                    rows++
                }
            }
        }

        // Printed rather than logged: this is a build task and the fingerprint
        // is the value the training script must stamp into the model.
        println("rows=$rows")
        println("features=${FeatureSchema.names.joinToString(",")}")
        println("schema_version=${FeatureSchema.VERSION}")
        println("schema_fingerprint=${FeatureSchema.fingerprint}")
        println("output=${output.toAbsolutePath()}")
    }

    /**
     * Missing is the empty string, which is what pandas and LightGBM read as
     * `NaN` without being told. Writing the literal `NaN` also works and
     * writing `0` silently does not, which is why this is a named function with
     * a comment rather than a `toString()`.
     */
    private fun formatFeature(value: Float): String = if (value.isNaN()) "" else value.toString()

    const val LABEL_COLUMN = "label"
    private const val DEFAULT_OUTPUT = "build/training/dataset.csv"
    private const val QUERIES = 4_000
    private const val SEED = 20260806L
}

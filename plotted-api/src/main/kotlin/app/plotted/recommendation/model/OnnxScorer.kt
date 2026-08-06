package app.plotted.recommendation.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs a trained ranking model in-process.
 *
 * ONNX rather than a Python sidecar: a second service on the request path buys a
 * deployment, a network hop and a new failure mode, to answer a question that
 * takes microseconds. The cost of the choice is that the model must be exported
 * faithfully, which is the subject of every check below.
 *
 * ### What this refuses to do
 *
 * Serve a model it cannot prove is for this feature vector. [load] compares the
 * schema fingerprint baked into the model's metadata against
 * [FeatureSchema.fingerprint], and a mismatch is a refusal with both values in
 * the message — not a warning, and certainly not a best-effort score.
 *
 * That is the whole design. A model reading a reordered vector does not fail; it
 * returns confident numbers drawn from a distribution it was never trained on,
 * and the only symptom is that recommendations get slightly worse. There is no
 * alert for "slightly worse".
 */
class OnnxScorer private constructor(
    private val session: OrtSession,
    private val environment: OrtEnvironment,
    val metadata: ModelMetadata,
) : AutoCloseable {
    /**
     * Scores a batch.
     *
     * Batched because the caller always has a whole candidate list and a single
     * session run over `n` rows is dramatically cheaper than `n` runs — the
     * per-call overhead dominates at this model size.
     */
    fun score(vectors: List<FloatArray>): DoubleArray {
        if (vectors.isEmpty()) return DoubleArray(0)
        vectors.forEachIndexed { row, vector ->
            require(vector.size == FeatureSchema.size) {
                "Row $row has ${vector.size} features, schema ${FeatureSchema.VERSION} has ${FeatureSchema.size}"
            }
        }

        val input = Array(vectors.size) { vectors[it] }
        return OnnxTensor.createTensor(environment, input).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                readScores(result, vectors.size)
            }
        }
    }

    /**
     * Pulls one score per row out of whatever shape the exporter produced.
     *
     * onnxmltools emits a regressor's output as `float[n][1]`, and some
     * converters flatten it to `float[n]`. Handling both here rather than
     * assuming one is not defensiveness for its own sake: the alternative is a
     * `ClassCastException` at the first real request, on a path that only runs
     * in production.
     */
    private fun readScores(result: OrtSession.Result, rows: Int): DoubleArray = when (val value = result[0].value) {
        is Array<*> -> DoubleArray(rows) { row ->
            when (val cell = value[row]) {
                is FloatArray -> cell.first().toDouble()
                is Float -> cell.toDouble()
                else -> error("Unexpected ONNX output cell type ${cell?.let { it::class.java.name }}")
            }
        }

        is FloatArray -> DoubleArray(rows) { value[it].toDouble() }
        else -> error("Unexpected ONNX output type ${value?.let { it::class.java.name }}")
    }

    override fun close() {
        session.close()
    }

    data class ModelMetadata(
        val schemaFingerprint: String,
        val schemaVersion: String,
        val modelVersion: String,
        val trainedOn: String?,
    ) {
        /**
         * Stamped on every logged decision so phase 7 can never pool rows from
         * two different scoring functions. A version string is the cheapest
         * thing that makes that impossible by accident.
         */
        val rankerVersion: String get() = "learned-$modelVersion"
    }

    /** Why a model was not loaded. Reported, never thrown past the caller. */
    sealed interface LoadFailure {
        data class NotFound(val path: Path) : LoadFailure

        data class SchemaMismatch(val modelFingerprint: String?, val expected: String) : LoadFailure

        data class Unreadable(val reason: String) : LoadFailure
    }

    sealed interface LoadResult {
        data class Loaded(val scorer: OnnxScorer) : LoadResult

        data class Refused(val failure: LoadFailure) : LoadResult
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OnnxScorer::class.java)

        const val FINGERPRINT_KEY = "plotted_schema_fingerprint"
        const val SCHEMA_VERSION_KEY = "plotted_schema_version"
        const val MODEL_VERSION_KEY = "plotted_model_version"
        const val TRAINED_ON_KEY = "plotted_trained_on"

        /**
         * Loads a model, or explains why it did not.
         *
         * Returns a result rather than throwing, because "there is no model
         * yet" is the normal state of this system and must not stop it booting.
         * A refusal is logged at warn and the caller falls back to the linear
         * ranker — which is a working recommender, not a degraded mode.
         */
        fun load(path: Path): LoadResult {
            if (!Files.isRegularFile(path)) {
                return LoadResult.Refused(LoadFailure.NotFound(path))
            }

            return try {
                val environment = OrtEnvironment.getEnvironment()
                val session = environment.createSession(path.toAbsolutePath().toString(), OrtSession.SessionOptions())
                val custom = session.metadata.customMetadata
                val fingerprint = custom[FINGERPRINT_KEY]

                if (fingerprint != FeatureSchema.fingerprint) {
                    // The check that makes training-serving skew impossible to
                    // ship silently. Both values go in the message because the
                    // first question anyone asks is "which one is stale".
                    session.close()
                    logger.warn(
                        "Refusing model at {}: schema fingerprint {} does not match {} ({} features, {})",
                        path,
                        fingerprint ?: "<absent>",
                        FeatureSchema.fingerprint,
                        FeatureSchema.size,
                        FeatureSchema.VERSION,
                    )
                    return LoadResult.Refused(LoadFailure.SchemaMismatch(fingerprint, FeatureSchema.fingerprint))
                }

                val metadata = ModelMetadata(
                    schemaFingerprint = fingerprint,
                    schemaVersion = custom[SCHEMA_VERSION_KEY] ?: FeatureSchema.VERSION,
                    modelVersion = custom[MODEL_VERSION_KEY] ?: "unversioned",
                    trainedOn = custom[TRAINED_ON_KEY],
                )
                logger.info("Loaded ranking model {} from {} (schema {})", metadata.modelVersion, path, metadata.schemaVersion)
                LoadResult.Loaded(OnnxScorer(session, environment, metadata))
            } catch (failure: Exception) {
                // Broad on purpose. ONNX Runtime is a JNI binding and its
                // failures arrive as anything from OrtException to
                // UnsatisfiedLinkError's wrapper; none of them is a reason for
                // the application not to start, because there is a working
                // ranker either way.
                logger.warn("Could not load ranking model at {}: {}", path, failure.message)
                LoadResult.Refused(LoadFailure.Unreadable(failure.message ?: failure::class.java.simpleName))
            }
        }
    }
}

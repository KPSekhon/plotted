package app.plotted.recommendation.model

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Path

@ConfigurationProperties(prefix = "plotted.model")
data class ModelProperties(
    /**
     * Where the exported model lives. Absent is the normal state and not an
     * error: the linear ranker is a working recommender, not a fallback mode.
     */
    val path: String = "models/ranker.onnx",
    /**
     * Off by default. A model that exists is not the same as a model anyone has
     * decided to serve, and the decision belongs to whoever deploys it rather
     * than to whoever copied a file into place.
     */
    val enabled: Boolean = false,
)

/**
 * The application's single view of "is there a learned ranker, and may we use it".
 *
 * Loaded once at startup rather than per request. An ONNX session is expensive
 * to create and cheap to share, and a per-request load would turn a microsecond
 * of inference into tens of milliseconds of file I/O.
 *
 * ### Why this is not wired into Tonight Mode yet
 *
 * It scores, and it is deliberately not on the request path. Two reasons, and
 * the first is the real one:
 *
 * 1. **Explanations.** The product's rule is that a reason must be a real
 *    feature contribution rather than prose that sounds like one. A gradient
 *    boosted tree does not hand you contributions; getting them means SHAP,
 *    which ONNX does not export. Shipping the learned ranking alongside the
 *    *linear* model's explanations would mean the interface confidently
 *    explaining a decision it did not make — which is the invented-prose failure
 *    this project has refused everywhere else, wearing a better costume.
 * 2. **Nothing has measured it.** There is no trained model and no outcome data
 *    to train one on. When there is, the harness in
 *    `app.plotted.recommendation.evaluation` scores it against the linear model
 *    and the priority baseline *before* it serves anyone.
 *
 * So this exists, loads, validates and scores — and the decision to put it in
 * front of a user is a separate one that has not been earned yet. That order is
 * the point of phase 7 preceding phase 8.
 */
@Component
class RankingModel(private val properties: ModelProperties) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val loaded: OnnxScorer? by lazy {
        if (!properties.enabled) {
            logger.info("Learned ranker disabled; serving the linear ranker")
            return@lazy null
        }
        when (val result = OnnxScorer.load(Path.of(properties.path))) {
            is OnnxScorer.LoadResult.Loaded -> result.scorer
            is OnnxScorer.LoadResult.Refused -> {
                logger.warn("Serving the linear ranker: {}", describe(result.failure))
                null
            }
        }
    }

    val scorer: OnnxScorer? get() = loaded

    val isActive: Boolean get() = loaded != null

    /**
     * What to stamp on a logged decision.
     *
     * The evaluation harness must never pool rows produced by two different
     * scoring functions, and this is the string that stops it. It changes the
     * moment the active model does.
     */
    val rankerVersion: String get() = loaded?.metadata?.rankerVersion ?: LINEAR_VERSION

    private fun describe(failure: OnnxScorer.LoadFailure): String = when (failure) {
        is OnnxScorer.LoadFailure.NotFound -> "no model at ${failure.path}"
        is OnnxScorer.LoadFailure.SchemaMismatch ->
            "model was trained against schema ${failure.modelFingerprint ?: "<absent>"}, " +
                "this build expects ${failure.expected}"
        is OnnxScorer.LoadFailure.Unreadable -> "model could not be read: ${failure.reason}"
    }

    @PreDestroy
    override fun close() {
        loaded?.close()
    }

    private companion object {
        const val LINEAR_VERSION = "linear-v1"
    }
}

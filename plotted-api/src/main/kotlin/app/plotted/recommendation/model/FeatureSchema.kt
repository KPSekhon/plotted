package app.plotted.recommendation.model

import app.plotted.recommendation.domain.Candidate
import app.plotted.recommendation.domain.TonightContext
import app.plotted.recommendation.domain.deadlineUrgency
import app.plotted.recommendation.domain.runtimeFit
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * The one description of what a feature vector is, shared by training and serving.
 *
 * ### The bug this file exists to prevent
 *
 * Training-serving skew: the features computed when the model was fitted differ
 * subtly from the ones computed when it is asked a question. A column reordered,
 * a rating divided by 10 on one side and not the other, a missing value encoded
 * as `0` in Python and `NaN` in Kotlin. **Nothing throws.** The model keeps
 * returning confident scores drawn from a distribution it was never trained on,
 * and the only symptom is that recommendations get quietly worse.
 *
 * It is the defining production failure of applied machine learning and it is
 * invisible to every test that checks one side in isolation.
 *
 * ### How it is prevented here
 *
 * Three things, in increasing order of how much they actually help:
 *
 * 1. **One ordered declaration.** [FEATURES] is the only place feature order
 *    lives. ONNX input is a positional `float[]`, so order *is* the contract.
 * 2. **[fingerprint]** — a hash over the schema version and the feature names in
 *    order. The training script writes it into the model's metadata; the loader
 *    compares it and **refuses to serve a model whose fingerprint does not
 *    match**, falling back to the linear ranker. A reordered or renamed feature
 *    therefore cannot ship silently; it becomes a startup refusal with a
 *    message naming both fingerprints.
 * 3. **Golden vectors.** The training script exports a sample of input vectors
 *    with the scores the model gave them, and `GoldenVectorTest` replays them
 *    through this JVM path. A fingerprint match proves the *shape* agrees; the
 *    golden vectors prove the *arithmetic* does.
 *
 * ### Missing values are NaN, deliberately
 *
 * LightGBM has native missing-value handling and treats `NaN` as absent.
 * Encoding a missing rating as `0.0` would tell the model "everybody hated it",
 * which is the same mistake the linear ranker avoids by renormalising — see
 * `Features.kt`. The two models solve it differently and that contrast is the
 * point: the linear model **redistributes** the weight of an absent feature, and
 * the tree model is simply **told it is absent** and learns its own split.
 *
 * Whether `NaN` survives the LightGBM → ONNX conversion is a real risk rather
 * than a theoretical one, and it is exactly what the golden vectors check.
 */
object FeatureSchema {
    /**
     * Bumped whenever [FEATURES] changes in any way that alters the vector.
     *
     * Changing this invalidates every existing model, which is the intended
     * effect: a model trained on a different vector is not a model for this
     * vector, however well it scores.
     */
    const val VERSION = "v1"

    /**
     * The feature vector, in order. **Order is the contract.**
     *
     * Reordering this list without bumping [VERSION] produces a model that
     * silently reads every feature as a different one. The fingerprint makes
     * that a refusal rather than a slow degradation.
     */
    val FEATURES: List<ModelFeature> = listOf(
        ModelFeature("priority") { candidate, _, _, _ ->
            // 1 is the highest, so it inverts. Same transform as the linear
            // ranker's PRIORITY feature, deliberately: two definitions of
            // "how much do they want it" is a skew bug with extra steps.
            (PRIORITY_LOWEST - candidate.priority + 1).toDouble() / PRIORITY_LOWEST
        },
        ModelFeature("runtime_fit") { candidate, context, _, _ ->
            context.availableMinutes?.let { budget ->
                candidate.watchMinutes?.let { runtimeFit(it, budget) }
            }
        },
        ModelFeature("runtime_minutes") { candidate, _, _, _ ->
            // The raw runtime as well as the fit. A tree can learn "series over
            // three hours are rarely finished" — a shape the linear model's
            // single fit score cannot express at all.
            candidate.watchMinutes?.toDouble()
        },
        ModelFeature("access") { candidate, _, subscribed, _ ->
            candidate.offers.maxOfOrNull { offer ->
                when {
                    offer.providerId in subscribed -> 1.0
                    offer.isFree -> FREE_ACCESS_SCORE
                    else -> UNSUBSCRIBED_ACCESS_SCORE
                }
            }
        },
        ModelFeature("offer_count") { candidate, _, _, _ ->
            // Present on many services is weak evidence of broad appeal, and it
            // is free to compute. Absent offers is 0 rather than NaN: "nothing
            // carries it" is a fact, not a gap in the data.
            candidate.offers.map { it.providerId }.distinct().size.toDouble()
        },
        ModelFeature("deadline_urgency") { candidate, _, _, today ->
            candidate.desiredByDate?.let { deadlineUrgency(it, today) }
        },
        ModelFeature("community_rating") { candidate, _, _, _ ->
            candidate.communityRating?.let { (it / MAXIMUM_RATING).coerceIn(0.0, 1.0) }
        },
        ModelFeature("is_series") { candidate, _, _, _ ->
            if (candidate.mediaType == "series") 1.0 else 0.0
        },
    )

    val names: List<String> get() = FEATURES.map { it.name }

    val size: Int get() = FEATURES.size

    /**
     * A stable hash of the schema, written into the model at training time and
     * checked at load time.
     *
     * Covers the version and the names *in order*, so any of renaming,
     * reordering, adding or removing changes it. It deliberately does **not**
     * cover the extraction lambdas: a Kotlin closure has no stable hash, and
     * pretending otherwise would make the fingerprint change on an unrelated
     * refactor and train everyone to ignore it. Extraction changes are what the
     * golden vectors catch — the two mechanisms cover different halves of the
     * problem, which is why both exist.
     */
    val fingerprint: String by lazy {
        val canonical = "$VERSION|${names.joinToString(",")}"
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * The vector for one candidate, in schema order.
     *
     * `Float` rather than `Double` because ONNX tree ensembles take `float32`
     * input, and doing the narrowing here — once, at the boundary — means the
     * golden vectors are compared at the precision the model actually sees.
     * Narrowing later would make a golden-vector mismatch look like a model
     * problem when it was a rounding one.
     */
    fun extract(candidate: Candidate, context: TonightContext, subscribedProviderIds: Set<UUID>, today: LocalDate): FloatArray =
        FloatArray(FEATURES.size) { index ->
            FEATURES[index].extract(candidate, context, subscribedProviderIds, today)?.toFloat() ?: Float.NaN
        }

    /** Named values, for logging and for making a golden-vector failure readable. */
    fun describe(vector: FloatArray): Map<String, Float> = names.zip(vector.toList()).toMap()

    private const val PRIORITY_LOWEST = 5
    private const val MAXIMUM_RATING = 10.0
    private const val FREE_ACCESS_SCORE = 0.8
    private const val UNSUBSCRIBED_ACCESS_SCORE = 0.3
    private const val FINGERPRINT_BYTES = 8
}

/**
 * One feature: a name and how to get it.
 *
 * The extractor returns null for "not known", which [FeatureSchema.extract]
 * turns into `NaN`. Returning 0.0 instead would be the single most common way to
 * poison a tree model, because 0 is a real and meaningful value for most of
 * these.
 */
class ModelFeature(
    val name: String,
    private val extractor: (Candidate, TonightContext, Set<UUID>, LocalDate) -> Double?,
) {
    fun extract(candidate: Candidate, context: TonightContext, subscribed: Set<UUID>, today: LocalDate): Double? =
        extractor(candidate, context, subscribed, today)
}

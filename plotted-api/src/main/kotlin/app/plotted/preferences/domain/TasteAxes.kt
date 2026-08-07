package app.plotted.preferences.domain

import kotlin.math.abs

/**
 * The axes a taste profile is expressed along.
 *
 * ### Why axes rather than genres
 *
 * TMDB has nineteen genres. Fitting nineteen weights from fifteen answers is not
 * a modelling choice, it is arithmetic that cannot work — most genres would
 * never appear in a pair, and the ones that did would carry the whole profile.
 *
 * Six axes can be learned from fifteen answers, and each one is a question a
 * person could actually be asked out loud: *lighter or heavier? faster or
 * slower? real or invented?* That matters beyond the fit, because the profile is
 * shown to the user and "you scored 0.7 on Science Fiction" is not something
 * anybody can agree or disagree with.
 *
 * ### Every axis is centred by construction
 *
 * Each returns a value in `[-1, 1]` where **0 means balanced**, so a title with
 * no attributes scores 0 and [BradleyTerry.match] puts it at exactly 0.5. The
 * alternative — centring on population means — would need catalogue-wide
 * statistics that shift every time the seed grows, so a profile fitted in March
 * would silently mean something different in June.
 *
 * The reference points that are not structural (what counts as "recent", what
 * counts as "acclaimed") are stated as constants below rather than derived, and
 * they are choices. They are recorded here so they can be argued with.
 */
enum class TasteAxis(val label: String, val positive: String, val negative: String) {
    /** Comedy and family at one end, drama and war at the other. */
    LEVITY("Levity", "lighter", "heavier"),

    /** Action and thriller against drama and documentary. */
    PACE("Pace", "faster", "slower"),

    /** Documentary and history against fantasy and science fiction. */
    GROUNDED("Grounding", "grounded", "invented"),

    /** A series is a commitment in a way a film is not. */
    COMMITMENT("Commitment", "a series", "a film"),

    /** How recent it is, against [RECENT_YEARS] years ago. */
    RECENCY("Recency", "newer", "older"),

    /** Community rating against [ACCLAIM_REFERENCE]. */
    ACCLAIM("Acclaim", "well reviewed", "overlooked"),
    ;

    companion object {
        val ordered: List<TasteAxis> = entries.toList()
        val size: Int get() = entries.size
        val labels: List<String> get() = entries.map { it.name }

        /** A title released this many years ago sits at 0 on [RECENCY]. */
        const val RECENT_YEARS = 5

        /** Ratings are on 0–10; this is the midpoint the axis is centred on. */
        const val ACCLAIM_REFERENCE = 7.0

        /**
         * Genres pulling each way. A genre in neither list contributes nothing
         * to that axis, which is correct — "Music" says nothing about pace.
         *
         * Names, not TMDB ids: the ids are stable but unreadable, and a mapping
         * nobody can check by eye is a mapping nobody checks.
         */
        val LIGHT_GENRES = setOf("Comedy", "Family", "Animation", "Music", "Romance")
        val HEAVY_GENRES = setOf("Drama", "War", "Crime", "Horror", "Thriller")
        val FAST_GENRES = setOf("Action", "Adventure", "Thriller", "Crime", "Science Fiction")
        val SLOW_GENRES = setOf("Drama", "Documentary", "History", "Romance")
        val GROUNDED_GENRES = setOf("Documentary", "History", "War", "Crime", "Drama")
        val INVENTED_GENRES = setOf("Fantasy", "Science Fiction", "Animation", "Horror")
    }
}

/**
 * What the model knows about one title, on the axes above.
 *
 * Built by the caller from catalogue data — genres, media type, release year,
 * community rating. Absent inputs contribute 0 to their axis rather than being
 * guessed, which is the same rule the rankers follow: an unrated film is not a
 * badly rated one.
 */
data class TitleAttributes(val values: DoubleArray) {
    init {
        require(values.size == TasteAxis.size) {
            "Expected ${TasteAxis.size} axes, got ${values.size}"
        }
    }

    operator fun get(axis: TasteAxis): Double = values[axis.ordinal]

    /** How far apart two titles are overall. Used to keep a ladder pair comparable. */
    fun distanceTo(other: TitleAttributes): Double = values.indices.sumOf { abs(values[it] - other.values[it]) }

    fun minus(other: TitleAttributes): DoubleArray = DoubleArray(values.size) { values[it] - other.values[it] }

    override fun equals(other: Any?): Boolean = this === other || (other is TitleAttributes && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        /** An average title: no genre lean, no age, no acclaim. Scores 0.5 against any profile. */
        val NEUTRAL = TitleAttributes(DoubleArray(TasteAxis.size))

        /**
         * Derives the axes from what the catalogue holds.
         *
         * @param genres genre names as TMDB gives them.
         * @param releaseYear null when unknown, contributing 0 rather than a guess.
         * @param communityRating 0–10, null when nobody has rated it.
         * @param currentYear passed in rather than read from a clock, so the same
         *   title produces the same attributes in a test as it does in a fit.
         */
        fun of(genres: Set<String>, isSeries: Boolean, releaseYear: Int?, communityRating: Double?, currentYear: Int): TitleAttributes {
            val values = DoubleArray(TasteAxis.size)
            values[TasteAxis.LEVITY.ordinal] = lean(genres, TasteAxis.LIGHT_GENRES, TasteAxis.HEAVY_GENRES)
            values[TasteAxis.PACE.ordinal] = lean(genres, TasteAxis.FAST_GENRES, TasteAxis.SLOW_GENRES)
            values[TasteAxis.GROUNDED.ordinal] = lean(genres, TasteAxis.GROUNDED_GENRES, TasteAxis.INVENTED_GENRES)
            values[TasteAxis.COMMITMENT.ordinal] = if (isSeries) 1.0 else -1.0
            values[TasteAxis.RECENCY.ordinal] = releaseYear?.let {
                // Linear in years, saturating so a 1940s film and a 1970s one are
                // both simply "old" rather than differing by a factor of three.
                ((it - (currentYear - TasteAxis.RECENT_YEARS)).toDouble() / TasteAxis.RECENT_YEARS).coerceIn(-1.0, 1.0)
            } ?: 0.0
            values[TasteAxis.ACCLAIM.ordinal] = communityRating?.let {
                ((it - TasteAxis.ACCLAIM_REFERENCE) / ACCLAIM_SPREAD).coerceIn(-1.0, 1.0)
            } ?: 0.0
            return TitleAttributes(values)
        }

        /**
         * Where a title sits between two opposing genre sets, in `[-1, 1]`.
         *
         * A title in both sets — a crime comedy — lands between them rather than
         * at an end, which is the honest answer. Belonging to neither gives 0,
         * and so does having no genres at all.
         */
        private fun lean(genres: Set<String>, positive: Set<String>, negative: Set<String>): Double {
            val forwards = genres.count { it in positive }
            val backwards = genres.count { it in negative }
            val total = forwards + backwards
            return if (total == 0) 0.0 else (forwards - backwards).toDouble() / total
        }

        /** Ratings mostly fall within about 1.5 points of the reference; beyond that the axis saturates. */
        private const val ACCLAIM_SPREAD = 1.5
    }
}

/** Mirrors the API. See docs/PROGRESS.md phase 11 for why each guard exists. */

/**
 * How long between being shown three options and choosing one.
 *
 * Every field is nullable for the same reason: a latency computed from no
 * observations is not zero, and zero is the *best possible* value here — so
 * reporting it would be the finest number in the product arrived at by having
 * no evidence at all.
 */
export interface DecisionLatency {
  readonly medianSeconds: number | null;
  readonly fastestSeconds: number | null;
  readonly slowestSeconds: number | null;
  /** Acceptances the median is built from. */
  readonly sampleSize: number;
  /** Acceptances that arrived more than four hours later and were left out. */
  readonly excludedAsStale: number;
}

/**
 * Of the picks somebody accepted, how many they actually finished.
 *
 * `rate` is null rather than 0 when nothing has been judged. And `judged`
 * deliberately excludes anything accepted in the last fortnight: counting a
 * recent pick as a failure would make the rate climb on its own as the log
 * aged, which looks exactly like the product improving.
 */
export interface CompletionRate {
  readonly rate: number | null;
  readonly completed: number;
  readonly judged: number;
  readonly tooRecentToJudge: number;
}

export interface EndCredits {
  readonly decisionLatency: DecisionLatency;
  readonly acceptedAndCompleted: CompletionRate;
  readonly recommendationsServed: number;
  /** Requests that returned nothing. Shown beside the rest, because refusing is a feature. */
  readonly nothingFitCount: number;
}

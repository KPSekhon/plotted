/** Mirrors the API. See docs/PILOT.md for what the axes mean and why there are six. */

export interface PilotOption {
  readonly titleId: string;
  readonly name: string;
  readonly mediaType: 'movie' | 'series';
  readonly releaseYear: number | null;
  readonly posterUrl: string | null;
}

export interface PilotQuestion {
  readonly left: PilotOption;
  readonly right: PilotOption;
  /** The axis this pair was chosen to isolate. Shown for transparency; not needed to answer. */
  readonly axis: string;
  readonly axisLabel: string;
  readonly position: number;
}

export interface PilotState {
  readonly question: PilotQuestion | null;
  /** Answers that count as evidence. Skips are not among them. */
  readonly answered: number;
  /** Questions declined. Recorded so they are not re-asked, and excluded from the fit. */
  readonly skipped: number;
  readonly total: number;
  readonly complete: boolean;
  /**
   * True when the ladder ran out of usable pairs before reaching `total` — the
   * catalogue is too small or too uniform to contrast. A different situation
   * from a finished questionnaire, and shown as one.
   */
  readonly exhausted: boolean;
}

export interface PilotAnswerRequest {
  readonly leftTitleId: string;
  readonly rightTitleId: string;
  /** Omitted to skip. */
  readonly chosenTitleId?: string;
}

/**
 * `NO_PREFERENCE` and `NOT_ASKED` are both ways of saying we do not know, and
 * they are not interchangeable: the first means we asked and you were balanced,
 * the second that the ladder never contrasted this axis.
 */
export type Verdict = 'LIKES' | 'DISLIKES' | 'NO_PREFERENCE' | 'NOT_ASKED';

export interface AxisOpinion {
  readonly axis: string;
  readonly label: string;
  readonly positive: string;
  readonly negative: string;
  readonly weight: number;
  /** Posterior standard deviation. What separates a finding from an unasked question. */
  readonly standardError: number;
  readonly verdict: Verdict;
  readonly stated: boolean;
  readonly sentence: string;
}

export interface PreferenceProfile {
  readonly observations: number;
  readonly converged: boolean;
  /** Whether the profile has anything it can defend saying. When false, ranking ignores it. */
  readonly informative: boolean;
  readonly axes: readonly AxisOpinion[];
}

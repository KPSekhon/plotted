export type AccessPolicy = 'active_subscriptions_only' | 'include_free' | 'any_subscription';

export interface Reason {
  readonly reason: string;
  /** How much of the score this accounted for, 0 to 1. */
  readonly share: number;
}

export interface Pick {
  /** 1 is the pick, 2 and 3 are backups. */
  readonly position: number;
  readonly titleId: string;
  readonly name: string;
  readonly mediaType: 'movie' | 'series';
  readonly posterUrl: string | null;
  /** The whole commitment: a film, or every episode of a series added up. */
  readonly watchMinutes: number | null;
  /**
   * One sitting — a film, or a typical episode. What the time budget was
   * actually measured against, because a series is watched in increments.
   */
  readonly sessionMinutes: number | null;
  /** True when `sessionMinutes` describes an episode rather than the whole title. */
  readonly perEpisode: boolean;
  /**
   * Which episode this actually is.
   *
   * Null for a film, and for a series whose episodes the catalogue does not
   * hold. `runtimeMinutes` here is that episode's own and can differ from
   * `sessionMinutes`, which is the typical episode the time budget was measured
   * against.
   */
  readonly nextEpisode: NextEpisodeRef | null;
  readonly availableOn: readonly string[];
  readonly score: number;
  /** Derived from the scored features, never generated prose. */
  readonly reasons: readonly Reason[];
  /** True when this slot was filled by exploration rather than by rank. */
  readonly exploration: boolean;
}

export interface Rejection {
  readonly reason: string;
  readonly explanation: string;
  readonly count: number;
}

export interface Diagnosis {
  readonly headline: string;
  readonly reasons: readonly Rejection[];
}

/**
 * An empty `picks` with a populated `diagnosis` is a successful response, not an
 * error. The constraints were the request; excluding everything is information.
 */
export interface TonightResponse {
  /**
   * The decision this answer came from. Pass it back when accepting a pick, so
   * the acceptance attaches to the exact item that was offered — carrying its
   * position and propensity — rather than merely to a title.
   */
  readonly requestId: string;
  readonly picks: readonly Pick[];
  readonly diagnosis: Diagnosis | null;
  readonly candidateCount: number;
  readonly eligibleCount: number;
}

export interface AcceptPickRequest {
  readonly titleId: string;
}

export const ACCESS_POLICY_LABELS: Readonly<Record<AccessPolicy, string>> = {
  active_subscriptions_only: 'Only what I pay for',
  include_free: 'Include free services',
  any_subscription: 'Anything streaming',
};

/** The episode to put on, rather than an average to reason about. */
export interface NextEpisodeRef {
  readonly seasonNumber: number;
  readonly episodeNumber: number;
  readonly name: string | null;
  /** This episode's own runtime, or null when upstream never gave one. Never an average. */
  readonly runtimeMinutes: number | null;
  /** False when nothing has been finished, so this is episode one rather than a resumption. */
  readonly started: boolean;
  /** Aired episodes still ahead, including this one. */
  readonly remainingEpisodes: number;
}

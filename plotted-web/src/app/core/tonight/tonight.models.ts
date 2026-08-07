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
  readonly watchMinutes: number | null;
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

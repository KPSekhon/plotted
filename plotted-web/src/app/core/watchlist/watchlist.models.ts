/** Mirrors the API. 1 is the highest priority and 5 the lowest. */
export type WatchStatus = 'pending' | 'in_progress' | 'completed' | 'abandoned' | 'unavailable';

export interface WatchlistTitle {
  readonly name: string;
  readonly mediaType: 'movie' | 'series';
  readonly releaseYear: number | null;
  readonly posterUrl: string | null;
  readonly watchMinutes: number | null;
}

export interface WatchlistItem {
  readonly id: string;
  readonly titleId: string;
  readonly priority: number;
  readonly status: WatchStatus;
  readonly addedAt: string;
  /**
   * When this item became completed. Null unless status is `completed`, and also
   * null on a completed item finished before the API recorded this — unknown
   * rather than zero, and the two must not be conflated.
   */
  readonly completedAt: string | null;
  readonly desiredByDate: string | null;
  readonly notes: string | null;
  /** Null when the title has been removed from the catalogue since it was added. */
  readonly title: WatchlistTitle | null;
}

export interface Watchlist {
  readonly id: string;
  readonly name: string;
  readonly items: readonly WatchlistItem[];
}

export interface AddWatchlistItemRequest {
  readonly titleId: string;
  readonly priority?: number;
  readonly desiredByDate?: string;
  readonly notes?: string;
}

export interface UpdateWatchlistItemRequest {
  readonly priority?: number;
  readonly status?: WatchStatus;
  readonly desiredByDate?: string;
  readonly clearDesiredByDate?: boolean;
  readonly notes?: string;
  readonly clearNotes?: boolean;
}

export interface CoveredTitle {
  readonly titleId: string;
  readonly name: string | null;
  readonly priority: number;
}

export interface ProviderCoverage {
  readonly providerId: string;
  readonly name: string;
  readonly slug: string;
  readonly logoUrl: string | null;
  readonly titleCount: number;
  /** Share of total priority weight, 0 to 1. Shares do not sum to 1 across providers. */
  readonly weightedShare: number;
  readonly titles: readonly CoveredTitle[];
}

export interface Coverage {
  readonly regionCode: string;
  readonly consideredTitles: number;
  /** Outstanding items nobody has checked. Reported, never scored. */
  readonly unknownTitles: number;
  readonly providers: readonly ProviderCoverage[];
  readonly attribution: string;
}

export const PRIORITY_LABELS: Readonly<Record<number, string>> = {
  1: 'Desperate to see',
  2: 'Keen',
  3: 'Interested',
  4: 'Curious',
  5: 'Someday',
};

export const STATUS_LABELS: Readonly<Record<WatchStatus, string>> = {
  pending: 'Not started',
  in_progress: 'Watching',
  completed: 'Finished',
  abandoned: 'Gave up',
  unavailable: 'Not available',
};

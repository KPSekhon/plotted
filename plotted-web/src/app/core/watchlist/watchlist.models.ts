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
  /**
   * True when this title is also blocked. It keeps its place on the list rather
   * than being deleted, but neither recommender will offer it while the block
   * stands — so the row says so instead of being quietly skipped.
   */
  readonly blocked: boolean;
  /** Null when the title has been removed from the catalogue since it was added. */
  readonly title: WatchlistTitle | null;
}

/**
 * A title the user has asked never to be recommended.
 *
 * Blocking suppresses Tonight Mode and the subscription optimiser. It does not
 * hide the title from catalogue search: hiding it there reads as a missing
 * catalogue entry rather than a preference being honoured, and it would leave no
 * way to change your mind.
 */
export interface BlockedTitle {
  readonly titleId: string;
  readonly reason: string | null;
  readonly blockedAt: string;
  /** Null when the title has been removed from the catalogue since it was blocked. */
  readonly title: WatchlistTitle | null;
}

export interface BlockedTitles {
  readonly blocked: readonly BlockedTitle[];
}

export interface BlockTitleRequest {
  readonly titleId: string;
  readonly reason?: string;
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

/**
 * Where you are in a series, and what comes next.
 *
 * Position only. This records which episode you finished, never how quickly you
 * got there, so nothing built on it may claim a viewing pace -- that needs
 * completion events over time, which Plotted does not store.
 */
export interface SeriesProgress {
  readonly titleId: string;
  /** Null until you have finished something. Absent history, not episode zero. */
  readonly lastCompleted: EpisodeRef | null;
  /**
   * The first aired episode you have not finished, or episode one when nothing
   * is recorded. Null only when there is genuinely nothing left to watch.
   */
  readonly next: NextEpisode | null;
  readonly remaining: RemainingEpisodes;
  /** True when nothing aired is left. Different from not having started. */
  readonly caughtUp: boolean;
  readonly updatedAt: string | null;
}

export interface EpisodeRef {
  readonly seasonNumber: number;
  readonly episodeNumber: number;
}

export interface NextEpisode extends EpisodeRef {
  readonly episodeId: string;
  readonly name: string | null;
  /** This episode's own runtime, or null when upstream never gave one. Never an average. */
  readonly runtimeMinutes: number | null;
}

/**
 * The count includes episodes with no known runtime; the minutes do not. So
 * nine episodes and three hours can mean nine episodes of which seven are
 * measured, which is the honest pair.
 */
export interface RemainingEpisodes {
  readonly episodes: number;
  readonly minutes: number | null;
}

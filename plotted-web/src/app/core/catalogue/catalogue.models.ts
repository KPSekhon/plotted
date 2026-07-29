export type MediaType = 'movie' | 'series';

export type MetadataStatus = 'stub' | 'partial' | 'complete' | 'failed';

/** A title Plotted has ingested. Has an id, so a watchlist can reference it. */
export interface Title {
  id: string;
  mediaType: MediaType;
  name: string;
  originalName: string | null;
  overview: string | null;
  releaseDate: string | null;
  posterUrl: string | null;
  communityRating: number | null;
  /**
   * A film's runtime, or a series' estimated total. Null means Plotted does not
   * know, and a time-constrained recommendation has to exclude the title rather
   * than guess that it fits.
   */
  watchMinutes: number | null;
  episodeCount: number | null;
  metadataStatus: MetadataStatus;
}

/** A TMDB result Plotted has not ingested. No id until it is. */
export interface DiscoverResult {
  externalId: string;
  mediaType: MediaType;
  name: string;
  releaseDate: string | null;
  overview: string | null;
  posterUrl: string | null;
}

export type AccessType = 'subscription' | 'free' | 'ads' | 'rent' | 'buy' | 'library';

export interface AvailabilityOffer {
  providerName: string;
  providerSlug: string;
  providerType: string;
  providerLogoUrl: string | null;
  accessType: AccessType;
  price: number | null;
  currency: string | null;
  deepLink: string | null;
  /** Where the claim came from. Shown, not hidden. */
  source: string;
  verifiedAt: string;
  /** 0 to 1. Below 1 means part of the upstream response could not be mapped. */
  confidence: number;
}

export interface Availability {
  regionCode: string;
  offers: AvailabilityOffer[];
  lastVerifiedAt: string | null;
  /** True when nothing here has been checked recently enough to rely on. */
  stale: boolean;
  attribution: string;
}

export interface IngestTitleRequest {
  mediaType: MediaType;
  tmdbId: number;
}

/** Ordered the way a viewer thinks about cost: free things first, paid last. */
export const ACCESS_TYPE_ORDER: AccessType[] = [
  'subscription',
  'free',
  'ads',
  'library',
  'rent',
  'buy',
];

export const ACCESS_TYPE_LABELS: Record<AccessType, string> = {
  subscription: 'Included with a subscription',
  free: 'Free',
  ads: 'Free with ads',
  library: 'Free with a library card',
  rent: 'Rent',
  buy: 'Buy',
};

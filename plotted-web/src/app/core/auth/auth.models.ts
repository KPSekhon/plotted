/**
 * Hand-written for Phase 1. These interfaces are replaced by the OpenAPI
 * generated client (`npm run generate:api`) as soon as `openapi/openapi.json`
 * has been produced -- see docs/adr/0005-openapi-client-over-pact.md. Until then
 * the shapes here are checked against the real API by the Cypress smoke test
 * rather than being assumed correct.
 */

export type OnboardingStatus =
  | 'registered'
  | 'region_selected'
  | 'services_selected'
  | 'pilot_started'
  | 'pilot_complete'
  | 'active';

export type AccessPolicy = 'active_subscriptions_only' | 'include_free' | 'all_access';
export type CommitmentPreference = 'low' | 'medium' | 'high';

export interface User {
  id: string;
  email: string;
  displayName: string;
  regionCode: string;
  timezone: string;
  preferredCurrency: string;
  onboardingStatus: OnboardingStatus;
  createdAt: string;
}

export interface Session {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  expiresAt: string;
  user: User;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  regionCode?: string;
  timezone?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * What starting a demo tells you about the account you were just given.
 *
 * No `user` field, deliberately: the demo endpoint sets the refresh cookie and
 * the client then takes the ordinary refresh path to get a real `Session`. One
 * extra round trip buys a single definition of what a session is, instead of a
 * second shape that can drift away from the first.
 */
export interface DemoStart {
  displayName: string;
  watchlistSize: number;
  subscriptions: readonly string[];
  /** True when the catalogue has not been seeded, so the demo has no titles. */
  catalogueIsEmpty: boolean;
}

export interface UserSettings {
  maximumMonthlyBudget: number | null;
  maximumActiveServices: number | null;
  maximumMonthlySwitches: number | null;
  defaultAvailableMinutes: number | null;
  defaultAccessPolicy: string;
  defaultNoveltyPreference: number;
  defaultCommitmentPreference: string;
  allowPaidRentals: boolean;
  maximumRentalPrice: number | null;
  allowPhysicalMedia: boolean;
  /**
   * Overrides the trailing eight-week viewing estimate. Surfaced because that
   * estimate drives cancellation advice, and advice from a hidden number is not
   * advice a user can argue with.
   */
  weeklyViewingMinutesOverride: number | null;
  updatedAt: string;
}

export type UserSettingsPatch = Partial<Omit<UserSettings, 'updatedAt'>>;

export type BillingPeriod = 'monthly' | 'quarterly' | 'annual';
export type SubscriptionStatus = 'active' | 'paused' | 'cancelled' | 'trial' | 'lapsed';

export interface Provider {
  readonly id: string;
  readonly name: string;
  readonly slug: string;
  readonly type: string;
  readonly logoUrl: string | null;
}

export interface ProviderList {
  readonly providers: readonly Provider[];
}

export interface Subscription {
  readonly id: string;
  readonly providerId: string;
  readonly providerName: string;
  readonly providerSlug: string;
  readonly providerLogoUrl: string | null;
  readonly planName: string;
  readonly billingPeriod: BillingPeriod;
  /** What the user said they pay per billing period. */
  readonly price: number;
  /** The same figure per month, so plans on different cycles compare. */
  readonly monthlyCost: number;
  readonly currency: string;
  readonly status: SubscriptionStatus;
  readonly startedOn: string;
  readonly renewsOn: string | null;
  readonly autoRenews: boolean;
  readonly cannotCancel: boolean;
  readonly commitmentEndsOn: string | null;
  readonly notes: string | null;
}

export interface SubscriptionList {
  readonly subscriptions: readonly Subscription[];
  /** Active and trial only. Cancelled rows are returned but cost nothing. */
  readonly monthlyTotal: number;
  readonly currency: string;
  readonly countedSubscriptions: number;
}

export interface CreateSubscriptionRequest {
  readonly providerId: string;
  readonly planName?: string;
  readonly billingPeriod?: BillingPeriod;
  readonly price: number;
  readonly currency?: string;
  readonly status?: SubscriptionStatus;
  readonly startedOn?: string;
  readonly renewsOn?: string;
  readonly commitmentEndsOn?: string;
  readonly autoRenews?: boolean;
  readonly cannotCancel?: boolean;
  readonly notes?: string;
}

export interface UpdateSubscriptionRequest {
  readonly status?: SubscriptionStatus;
  readonly renewsOn?: string;
  readonly clearRenewsOn?: boolean;
  readonly autoRenews?: boolean;
  readonly cannotCancel?: boolean;
  readonly notes?: string;
  readonly clearNotes?: boolean;
}

export const BILLING_PERIOD_LABELS: Readonly<Record<BillingPeriod, string>> = {
  monthly: 'Monthly',
  quarterly: 'Every 3 months',
  annual: 'Yearly',
};

export const SUBSCRIPTION_STATUS_LABELS: Readonly<Record<SubscriptionStatus, string>> = {
  active: 'Active',
  trial: 'Free trial',
  paused: 'Paused',
  cancelled: 'Cancelled',
  lapsed: 'Lapsed',
};

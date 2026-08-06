/**
 * Cancel Culture: the subscription plan, month by month.
 *
 * `solved` carries a plan. `infeasible` and `nothing_to_plan` carry a diagnosis
 * instead, and both are successful responses: the limits were the request, so
 * relaxing one quietly would answer a different question, and an empty watchlist
 * has no basis for advice at all.
 */
export type PlanStatus = 'solved' | 'infeasible' | 'nothing_to_plan';

export interface ServiceRef {
  readonly providerId: string;
  readonly name: string;
}

export interface MonthPlan {
  /** 0 is this month. */
  readonly month: number;
  readonly monthlyCents: number;
  readonly subscribed: readonly ServiceRef[];
  readonly started: readonly ServiceRef[];
  readonly stopped: readonly ServiceRef[];
}

/** Each component on 0..1, recomputed exactly rather than read out of the solver. */
export interface PlanObjective {
  readonly coverage: number;
  readonly costFraction: number;
  readonly switchFraction: number;
  readonly weighted: number;
}

export interface PlanCoveredTitle {
  readonly titleId: string;
  readonly name: string;
  readonly month: number;
  readonly providerId: string;
  readonly providerName: string | null;
}

/** `not_carried` — no priced service has it. `not_chosen` — the plan could not fit it. */
export type UncoveredReason = 'not_carried' | 'not_chosen';

export interface UncoveredTitle {
  readonly titleId: string;
  readonly name: string;
  readonly priorityPoints: number;
  readonly reason: UncoveredReason;
  readonly availableOn: readonly string[];
}

export interface Sensitivity {
  readonly constraint: string;
  readonly relaxedBy: string;
  readonly coverageDelta: number;
  readonly monthlyCentsDelta: number;
}

export interface ExcludedTitle {
  readonly titleId: string;
  readonly name: string;
  readonly providerNames: readonly string[];
}

/**
 * Watchlist items the optimiser was never shown, and why. Rendered rather than
 * dropped: a title missing because nobody has checked it is a different fact
 * from one no plan could afford, and the user cannot tell them apart from a
 * coverage percentage.
 */
export interface Excluded {
  readonly freeToWatch: readonly ExcludedTitle[];
  readonly neverChecked: readonly ExcludedTitle[];
  readonly unpricedService: readonly ExcludedTitle[];
}

export interface PlanDiagnosis {
  readonly explanation: string;
  readonly bindingConstraint: string | null;
}

export interface PlanResponse {
  readonly status: PlanStatus;
  readonly horizonMonths: number;
  readonly months: readonly MonthPlan[];
  readonly diagnosis: PlanDiagnosis | null;
  readonly objective: PlanObjective | null;
  readonly totalCents: number | null;
  readonly covered: readonly PlanCoveredTitle[];
  readonly uncovered: readonly UncoveredTitle[];
  readonly sensitivity: readonly Sensitivity[];
  readonly excluded: Excluded;
  /**
   * Problems an independent reimplementation of the rules found in the solver's
   * own answer. Always empty in a healthy system; shown loudly when not, because
   * a wrong plan the user can see beats one they cannot.
   */
  readonly violations: readonly string[];
  readonly solveMillis: number | null;
}

export interface PlanQuery {
  readonly horizonMonths: number;
  readonly maximumMonthlyCents: number | null;
  readonly maximumActiveServices: number | null;
  readonly maximumMonthlySwitches: number | null;
  readonly coverageWeight: number | null;
}

export const CONSTRAINT_LABELS: Readonly<Record<string, string>> = {
  maximumActiveServices: 'services at once',
  maximumMonthlyBudget: 'monthly budget',
  maximumMonthlySwitches: 'changes per month',
};

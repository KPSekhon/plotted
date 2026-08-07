/** Mirrors the API. */

export type AlertSeverity = 'info' | 'warning' | 'urgent';

export interface Alert {
  readonly id: string;
  /** For example `availability.left`. */
  readonly alertType: string;
  readonly severity: AlertSeverity;
  readonly titleId: string | null;
  readonly message: string;
  readonly createdAt: string;
}

export interface Alerts {
  readonly alerts: readonly Alert[];
}

export interface UpdateAlertRequest {
  readonly status: 'read' | 'dismissed';
}

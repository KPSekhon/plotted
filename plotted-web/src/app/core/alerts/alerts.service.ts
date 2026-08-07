import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { Alerts, UpdateAlertRequest } from './alerts.models';

/**
 * What Plotted has to say to the signed-in user.
 *
 * Unread only. An alert that has been read or dismissed has done its job.
 */
@Injectable({ providedIn: 'root' })
export class AlertsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  unread(): Observable<Alerts> {
    return this.http.get<Alerts>(`${this.baseUrl}/alerts`);
  }

  settle(alertId: string, request: UpdateAlertRequest): Observable<void> {
    return this.http.patch<void>(`${this.baseUrl}/alerts/${alertId}`, request);
  }
}

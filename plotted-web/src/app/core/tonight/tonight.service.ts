import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { AcceptPickRequest, AccessPolicy, TonightResponse } from './tonight.models';

@Injectable({ providedIn: 'root' })
export class TonightService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  /**
   * `availableMinutes` is omitted rather than zeroed when there is no limit.
   * Zero is a different claim — it would mean "I have no time at all" and filter
   * everything out.
   */
  recommend(availableMinutes: number | null, accessPolicy: AccessPolicy): Observable<TonightResponse> {
    let params = new HttpParams().set('accessPolicy', accessPolicy);
    if (availableMinutes !== null) {
      params = params.set('availableMinutes', availableMinutes);
    }
    return this.http.get<TonightResponse>(`${this.baseUrl}/tonight`, { params });
  }

  /**
   * Records that the user is watching one of the picks they were offered.
   *
   * Takes the `requestId` from the response that produced them, so the choice
   * attaches to a specific served item. "They watched this title" is a much
   * weaker fact than "they chose it out of the three offered, from position two"
   * — and only the second can be evaluated against.
   */
  accept(requestId: string, request: AcceptPickRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/tonight/${requestId}/accept`, request);
  }
}

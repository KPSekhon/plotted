import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { AccessPolicy, TonightResponse } from './tonight.models';

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
}

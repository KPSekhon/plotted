import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { PlanQuery, PlanResponse } from './plan.models';

@Injectable({ providedIn: 'root' })
export class PlanService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  /**
   * Every limit is omitted rather than zeroed when the user has not set one.
   * Zero is a real and different request — a $0 budget, or "change nothing this
   * month" — and the API treats it that way, so sending one by accident would
   * silently produce a much narrower answer than the user asked for.
   */
  plan(query: PlanQuery): Observable<PlanResponse> {
    let params = new HttpParams().set('horizonMonths', query.horizonMonths);
    if (query.maximumMonthlyCents !== null) {
      params = params.set('maximumMonthlyCents', query.maximumMonthlyCents);
    }
    if (query.maximumActiveServices !== null) {
      params = params.set('maximumActiveServices', query.maximumActiveServices);
    }
    if (query.maximumMonthlySwitches !== null) {
      params = params.set('maximumMonthlySwitches', query.maximumMonthlySwitches);
    }
    if (query.coverageWeight !== null) {
      params = params.set('coverageWeight', query.coverageWeight);
    }
    return this.http.get<PlanResponse>(`${this.baseUrl}/plan`, { params });
  }
}

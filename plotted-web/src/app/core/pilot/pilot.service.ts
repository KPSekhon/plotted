import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { PilotAnswerRequest, PilotState, PreferenceProfile } from './pilot.models';

/**
 * Pilot Season.
 *
 * `answer` returns the state that follows, so the questionnaire costs one
 * request per question rather than an answer plus a re-fetch — which also means
 * the progress the user sees is the progress the server recorded.
 */
@Injectable({ providedIn: 'root' })
export class PilotService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  next(): Observable<PilotState> {
    return this.http.get<PilotState>(`${this.baseUrl}/pilot`);
  }

  /** Omit `chosenTitleId` to skip. A skip is recorded as declined, never as a preference. */
  answer(request: PilotAnswerRequest): Observable<PilotState> {
    return this.http.post<PilotState>(`${this.baseUrl}/pilot/answers`, request);
  }

  /** 204 with an empty body until at least one question has been answered. */
  profile(): Observable<PreferenceProfile | null> {
    return this.http.get<PreferenceProfile | null>(`${this.baseUrl}/pilot/profile`);
  }

  reset(): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/pilot/answers`);
  }
}

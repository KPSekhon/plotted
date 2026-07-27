import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { UserSettings, UserSettingsPatch } from '../auth/auth.models';

@Injectable({ providedIn: 'root' })
export class UserSettingsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  get(): Observable<UserSettings> {
    return this.http.get<UserSettings>(`${this.baseUrl}/users/me/settings`);
  }

  /** Partial update: omitted fields are left alone by the server. */
  patch(changes: UserSettingsPatch): Observable<UserSettings> {
    return this.http.patch<UserSettings>(`${this.baseUrl}/users/me/settings`, changes);
  }
}

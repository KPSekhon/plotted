import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import { DemoStart, LoginRequest, RegisterRequest, Session, User } from './auth.models';

/**
 * Session state, held in Angular Signals.
 *
 * The access token lives in memory only and is never written to localStorage:
 * anything readable by JavaScript is readable by injected JavaScript. Continuity
 * across a page reload comes from the HttpOnly refresh cookie instead, which is
 * why {@link restore} runs at application start.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private readonly session = signal<Session | null>(null);
  private readonly restored = signal(false);

  readonly user = computed<User | null>(() => this.session()?.user ?? null);
  readonly accessToken = computed<string | null>(() => this.session()?.accessToken ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);

  /** False until the initial refresh attempt settles, so guards do not redirect too early. */
  readonly isReady = this.restored.asReadonly();

  register(request: RegisterRequest): Observable<Session> {
    return this.http
      .post<Session>(`${this.baseUrl}/auth/register`, request, { withCredentials: true })
      .pipe(tap((session) => this.session.set(session)));
  }

  login(request: LoginRequest): Observable<Session> {
    return this.http
      .post<Session>(`${this.baseUrl}/auth/login`, request, { withCredentials: true })
      .pipe(tap((session) => this.session.set(session)));
  }

  /**
   * Starts a demo session: a throwaway account with a watchlist already on it.
   *
   * The demo endpoint sets the refresh cookie, and the ordinary refresh path
   * then turns that into a `Session`. Going through `refresh` rather than
   * reading the demo response's own token is the point — there is one definition
   * of what a signed-in session is, and a second one that only demo users take
   * is a second one that can be wrong without anybody noticing.
   */
  startDemo(): Observable<DemoStart> {
    return this.http
      .post<DemoStart>(`${this.baseUrl}/demo/session`, {}, { withCredentials: true })
      .pipe(switchMap((demo) => this.refresh().pipe(map(() => demo))));
  }

  /**
   * Exchanges the refresh cookie for a fresh access token. Every call rotates the
   * refresh token, so this must not be issued concurrently with itself -- the
   * server treats a second use of the same token as a leak and ends the session.
   */
  refresh(): Observable<Session | null> {
    return this.http
      .post<Session>(`${this.baseUrl}/auth/refresh`, {}, { withCredentials: true })
      .pipe(
        tap((session) => this.session.set(session)),
        catchError(() => {
          this.session.set(null);
          return of(null);
        }),
      );
  }

  logout(): Observable<void> {
    return this.http
      .post<void>(`${this.baseUrl}/auth/logout`, {}, { withCredentials: true })
      .pipe(
        tap(() => this.session.set(null)),
        catchError(() => {
          // The local session goes regardless; a failed revoke is the server's
          // problem to retry, not a reason to keep the user signed in here.
          this.session.set(null);
          return of(void 0);
        }),
      );
  }

  /** Runs once at startup. Resolves whether or not a session was recovered. */
  restore(): Observable<Session | null> {
    return this.refresh().pipe(tap(() => this.restored.set(true)));
  }

  clearSession(): void {
    this.session.set(null);
  }
}

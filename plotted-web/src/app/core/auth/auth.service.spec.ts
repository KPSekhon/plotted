import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { Session } from './auth.models';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  const session: Session = {
    accessToken: 'access-token',
    tokenType: 'Bearer',
    expiresIn: 900,
    expiresAt: '2026-07-26T18:15:00Z',
    user: {
      id: '11111111-2222-3333-4444-555555555555',
      email: 'kanwar@example.com',
      displayName: 'Kanwar',
      regionCode: 'CA',
      timezone: 'America/Toronto',
      preferredCurrency: 'CAD',
      onboardingStatus: 'registered',
      createdAt: '2026-07-26T18:00:00Z',
      isDemo: false,
    },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('turns a demo start into a real session by taking the ordinary refresh path', () => {
    let started: { displayName: string } | undefined;
    service.startDemo().subscribe((demo) => (started = demo));

    const start = http.expectOne('/api/v1/demo/session');
    expect(start.request.method).toBe('POST');
    // The cookie is the whole payload of that response, so credentials must be
    // sent and stored or the refresh below has nothing to exchange.
    expect(start.request.withCredentials).toBe(true);
    start.flush({
      displayName: 'Demo visitor',
      watchlistSize: 12,
      subscriptions: ['Netflix', 'Crave'],
      catalogueIsEmpty: false,
    });

    // Then the ordinary refresh, so there is one definition of a session rather
    // than a second one only demo users ever take.
    http.expectOne('/api/v1/auth/refresh').flush(session);

    expect(started?.displayName).toBe('Demo visitor');
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()?.displayName).toBe('Kanwar');
  });

  it('starts signed out', () => {
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.accessToken()).toBeNull();
  });

  it('holds the session in memory after signing in', () => {
    service.login({ email: 'kanwar@example.com', password: 'a-long-passphrase' }).subscribe();

    http.expectOne('/api/v1/auth/login').flush(session);

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.user()?.email).toBe('kanwar@example.com');
    expect(service.accessToken()).toBe('access-token');
  });

  it('never writes the access token to browser storage', () => {
    service.login({ email: 'kanwar@example.com', password: 'a-long-passphrase' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(session);

    // Anything readable by JavaScript is readable by injected JavaScript.
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(sessionStorage.getItem('accessToken')).toBeNull();
    expect(JSON.stringify(localStorage)).not.toContain('access-token');
  });

  it('sends credentials on auth calls so the HttpOnly refresh cookie travels', () => {
    service.refresh().subscribe();

    const request = http.expectOne('/api/v1/auth/refresh');

    expect(request.request.withCredentials).toBeTrue();
    request.flush(session);
  });

  it('clears the session when the refresh token is no longer valid', () => {
    service.login({ email: 'kanwar@example.com', password: 'a-long-passphrase' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(session);

    service.refresh().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush(
      { code: 'TOKEN_INVALID', status: 401 },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(service.isAuthenticated()).toBeFalse();
  });

  it('signs out locally even when the revoke call fails', () => {
    service.login({ email: 'kanwar@example.com', password: 'a-long-passphrase' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(session);

    service.logout().subscribe();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 500, statusText: 'Server Error' });

    expect(service.isAuthenticated()).toBeFalse();
  });
});

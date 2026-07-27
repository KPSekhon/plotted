import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Attaches the bearer token, and on a single 401 tries one silent refresh before
 * giving up.
 *
 * The retry is deliberately not generalised: refresh tokens rotate, so two
 * concurrent refreshes would present the same token twice and the server would
 * -- correctly -- read that as a leak and revoke the session. Endpoints under
 * `/auth` are excluded for the same reason.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);

  const isAuthEndpoint = request.url.includes('/auth/');
  const token = auth.accessToken();

  const authorised =
    token && !isAuthEndpoint
      ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : request;

  return next(authorised).pipe(
    catchError((error: unknown) => {
      const shouldRetry =
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !isAuthEndpoint &&
        token !== null;

      if (!shouldRetry) {
        return throwError(() => error);
      }

      return auth.refresh().pipe(
        switchMap((session): Observable<never> | ReturnType<typeof next> => {
          if (!session) {
            return throwError(() => error);
          }
          return next(
            request.clone({ setHeaders: { Authorization: `Bearer ${session.accessToken}` } }),
          );
        }),
      );
    }),
  );
};

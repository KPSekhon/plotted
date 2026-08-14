import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/** Sends signed-out visitors to the sign-in page, remembering where they were headed. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/sign-in'], { queryParams: { next: state.url } });
};

/** Keeps a signed-in user off the sign-in and sign-up pages. */
export const anonymousOnlyGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.isAuthenticated() ? router.createUrlTree(['/']) : true;
};

/*
 * `/` is two different pages, chosen by whether anyone is signed in.
 *
 * `canMatch` rather than a redirect, deliberately. A redirect would put the
 * landing page on its own path and bounce visitors to it, which means the URL
 * somebody shares or a recruiter is handed is not the page they land on — and a
 * signed-in user reloading would visibly flick through the marketing page on
 * the way home. Matching instead means one URL that resolves to the right
 * component the first time, and neither component ever loads for the wrong
 * audience.
 *
 * Safe because `APP_INITIALIZER` awaits the token refresh before the first
 * route resolves, so these are never asked before the answer is known.
 */
export const signedOutMatch: CanMatchFn = () => !inject(AuthService).isAuthenticated();

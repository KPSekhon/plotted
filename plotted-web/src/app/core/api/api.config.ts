import { InjectionToken } from '@angular/core';

/**
 * Base path for the Plotted API.
 *
 * In development the Angular dev server proxies `/api` to the Spring
 * application (see `src/proxy.conf.json`), so requests are same-origin and the
 * HttpOnly refresh cookie behaves exactly as it does in production. That is
 * deliberate: authentication bugs that only appear under real cookie rules are
 * the kind you find late.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => '/api/v1',
});

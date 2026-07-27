import { Routes } from '@angular/router';

import { anonymousOnlyGuard, authGuard } from './core/auth/auth.guard';

/**
 * Phase 1 ships the routes that are actually implemented. Tonight Mode (Queue
 * Theory) and the subscription optimiser (Cancel Culture) arrive in phases 4 and
 * 5; there are no placeholder screens for them, because a demo that navigates to
 * an empty page is worse than one with fewer links.
 */
export const routes: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage),
    title: 'Plotted',
  },
  {
    path: 'settings',
    canActivate: [authGuard],
    loadComponent: () => import('./features/settings/settings.page').then((m) => m.SettingsPage),
    title: 'Settings · Plotted',
  },
  {
    path: 'sign-in',
    canActivate: [anonymousOnlyGuard],
    loadComponent: () => import('./features/auth/sign-in.page').then((m) => m.SignInPage),
    title: 'Sign in · Plotted',
  },
  {
    path: 'sign-up',
    canActivate: [anonymousOnlyGuard],
    loadComponent: () => import('./features/auth/sign-up.page').then((m) => m.SignUpPage),
    title: 'Create an account · Plotted',
  },
  {
    // Public: the attribution obligations apply whether or not anyone is signed in.
    path: 'legal/data-sources',
    loadComponent: () =>
      import('./features/legal/data-sources.page').then((m) => m.DataSourcesPage),
    title: 'Data sources · Plotted',
  },
  {
    path: '**',
    loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
    title: 'Not found · Plotted',
  },
];

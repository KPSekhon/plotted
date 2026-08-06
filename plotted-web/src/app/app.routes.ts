import { Routes } from '@angular/router';

import { anonymousOnlyGuard, authGuard } from './core/auth/auth.guard';

/**
 * Only routes that are actually implemented. Tonight Mode (Queue Theory) landed
 * in phase 4 and Cancel Culture, the subscription optimiser, in phase 5 — a
 * route appears here when its screen does, because a demo that navigates to an
 * empty page is worse than one with fewer links.
 */
export const routes: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage),
    title: 'Plotted',
  },
  {
    path: 'search',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/catalogue/catalogue-search.page').then((m) => m.CatalogueSearchPage),
    title: 'Search · Plotted',
  },
  {
    path: 'titles/:titleId',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/catalogue/title-detail.page').then((m) => m.TitleDetailPage),
    title: 'Title · Plotted',
  },
  {
    path: 'tonight',
    canActivate: [authGuard],
    loadComponent: () => import('./features/tonight/tonight.page').then((m) => m.TonightPage),
    title: 'Tonight · Plotted',
  },
  {
    path: 'plan',
    canActivate: [authGuard],
    loadComponent: () => import('./features/plan/plan.page').then((m) => m.PlanPage),
    title: 'Cancel Culture · Plotted',
  },
  {
    path: 'watchlist',
    canActivate: [authGuard],
    loadComponent: () => import('./features/watchlist/watchlist.page').then((m) => m.WatchlistPage),
    title: 'Your list · Plotted',
  },
  {
    path: 'coverage',
    canActivate: [authGuard],
    loadComponent: () => import('./features/coverage/coverage.page').then((m) => m.CoveragePage),
    title: 'Coverage · Plotted',
  },
  {
    path: 'subscriptions',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/subscriptions/subscriptions.page').then((m) => m.SubscriptionsPage),
    title: 'Subscriptions · Plotted',
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
    // Public: someone evaluating the project should not have to sign up to read
    // what it is.
    path: 'about',
    loadComponent: () => import('./features/about/about.page').then((m) => m.AboutPage),
    title: 'About · Plotted',
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

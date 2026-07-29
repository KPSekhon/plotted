import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';

/**
 * The signed-in landing page.
 *
 * Deliberately close to empty, and that is the product argument rather than an
 * unfinished screen: Plotted exists because too many options is the problem. So
 * home offers one action. Build status and what the product is for live on the
 * about page, where someone evaluating the project can find them and someone who
 * just wants to watch something is not made to read them.
 */
@Component({
  selector: 'plotted-home',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (auth.user(); as user) {
      <section class="hero">
        <p class="eyebrow">{{ greeting() }}, {{ user.displayName }}</p>
        <h1>What are you watching tonight?</h1>
        <p class="lede muted">
          Search the catalogue and see where anything streams in {{ user.regionCode }}.
        </p>

        <a class="primary-action" routerLink="/search">
          Search the catalogue
          <span aria-hidden="true">&rarr;</span>
        </a>

        <p class="secondary muted">
          Tonight Mode is not built yet.
          <a routerLink="/about">See what is coming</a>.
        </p>
      </section>
    }
  `,
  styles: `
    .hero {
      max-width: 38rem;
      /* Sits a little off the top edge: the page has one thing on it, and
         pinning that to the header would look like a mistake. */
      margin-top: clamp(1rem, 6vh, 4rem);
    }

    .hero h1 {
      margin: 0.35rem 0 0.75rem;
    }

    .lede {
      font-size: 1.05rem;
      margin-bottom: 2rem;
    }

    .primary-action {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.75rem 1.5rem;
      border-radius: 999px;
      background: var(--plotted-accent);
      color: #1a1408;
      font-weight: 600;
      text-decoration: none;
      transition: transform 0.12s ease, background-color 0.12s ease;
    }

    .primary-action:hover {
      background: #ffc65c;
      transform: translateX(2px);
    }

    .secondary {
      margin: 1.75rem 0 0;
      font-size: 0.875rem;
    }

    .secondary a {
      color: var(--plotted-text-muted);
    }

    .secondary a:hover {
      color: var(--plotted-accent);
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);

  /** A small touch, but this is an evening product and it should know that. */
  protected greeting(): string {
    const hour = new Date().getHours();
    if (hour < 12) return 'Morning';
    if (hour < 17) return 'Afternoon';
    return 'Evening';
  }
}

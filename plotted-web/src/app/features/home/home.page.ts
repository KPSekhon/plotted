import { DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';

/**
 * The signed-in landing page.
 *
 * Deliberately close to empty. The product's whole argument is that too many
 * options is the problem, so the home screen offers one obvious action and an
 * honest account of what is not built yet, rather than filling the space with
 * panels to look busy.
 */
@Component({
  selector: 'plotted-home',
  standalone: true,
  imports: [DatePipe, RouterLink],
  template: `
    @if (auth.user(); as user) {
      <section class="hero">
        <p class="eyebrow">{{ greeting() }}, {{ user.displayName }}</p>
        <h1>What are you watching tonight?</h1>
        <p class="lede muted">
          Plotted is being built one phase at a time. The catalogue is live &mdash; search it, add
          titles, and see where they stream in {{ user.regionCode }}.
        </p>
        <a class="primary-action" routerLink="/search">
          Search the catalogue
          <span aria-hidden="true">&rarr;</span>
        </a>
      </section>

      <div class="cards">
        <section class="panel">
          <h2>Account</h2>
          <dl class="facts">
            <dt>Email</dt>
            <dd>{{ user.email }}</dd>
            <dt>Region</dt>
            <dd>{{ user.regionCode }} &middot; {{ user.preferredCurrency }}</dd>
            <dt>Time zone</dt>
            <dd>{{ user.timezone }}</dd>
            <dt>Joined</dt>
            <dd>{{ user.createdAt | date: 'longDate' }}</dd>
          </dl>
          <a class="quiet-link" routerLink="/settings">Edit your defaults &rarr;</a>
        </section>

        <section class="panel">
          <h2>What is built</h2>
          <ol class="phases">
            <li class="done"><span>Accounts, sessions, the Canadian schema</span></li>
            <li class="done"><span>Catalogue, availability and nightly snapshots</span></li>
            <li><span>Watchlists and platform coverage</span></li>
            <li><span>Tonight Mode &mdash; one pick, two backups, a reason</span></li>
            <li><span>Cancel Culture &mdash; which services to actually pay for</span></li>
          </ol>
          <p class="faint note">
            There is nothing to recommend yet, so nothing here pretends to recommend anything.
          </p>
        </section>
      </div>
    }
  `,
  styles: `
    .hero {
      max-width: 40rem;
      margin-bottom: 3rem;
    }

    .hero h1 {
      margin: 0.35rem 0 0.75rem;
    }

    .lede {
      font-size: 1.02rem;
      margin-bottom: 1.75rem;
    }

    .primary-action {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.7rem 1.4rem;
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

    .cards {
      display: grid;
      gap: 1rem;
      grid-template-columns: repeat(auto-fit, minmax(19rem, 1fr));
    }

    .facts {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 0.35rem 1.25rem;
      margin: 0 0 1rem;
      font-size: 0.9rem;
    }

    .facts dt {
      color: var(--plotted-text-faint);
    }

    .facts dd {
      margin: 0;
      overflow-wrap: anywhere;
    }

    .quiet-link {
      font-size: 0.875rem;
      color: var(--plotted-text-muted);
      text-decoration: none;
    }

    .quiet-link:hover {
      color: var(--plotted-accent);
    }

    .phases {
      list-style: none;
      counter-reset: phase;
      padding: 0;
      margin: 0 0 1rem;
      font-size: 0.9rem;
    }

    .phases li {
      counter-increment: phase;
      display: flex;
      align-items: baseline;
      gap: 0.7rem;
      padding: 0.3rem 0;
      color: var(--plotted-text-faint);
    }

    .phases li::before {
      content: counter(phase);
      flex-shrink: 0;
      width: 1.35rem;
      height: 1.35rem;
      border-radius: 999px;
      border: 1px solid var(--plotted-border-strong);
      display: grid;
      place-items: center;
      font-size: 0.7rem;
      font-variant-numeric: tabular-nums;
    }

    .phases li.done {
      color: var(--plotted-text);
    }

    .phases li.done::before {
      content: '✓';
      background: var(--plotted-accent-soft);
      border-color: transparent;
      color: var(--plotted-accent);
    }

    .note {
      font-size: 0.8rem;
      margin: 0;
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

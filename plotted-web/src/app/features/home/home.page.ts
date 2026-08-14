import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { HeroRouteComponent } from '../../shared/map/hero-route.component';
import { AlertListComponent } from '../alerts/alert-list.component';

/**
 * The signed-in landing page.
 *
 * Deliberately close to empty, and that is the product argument rather than an
 * unfinished screen: Plotted exists because too many options is the problem. So
 * home offers one action. Build status and what the product is for live on the
 * about page, where someone evaluating the project can find them and someone who
 * just wants to watch something is not made to read them.
 *
 * The emptiness is now doing visible work. A streaming service opens on a wall
 * of posters; this opens on a single route ending at a single X. That contrast
 * *is* the pitch, and it is made in the layout rather than in a sentence
 * claiming it.
 *
 * The mono block above the question is deliberately not decoration. It states
 * the context the recommendation will actually be made in — the hour, the
 * region — so the answer on the next screen arrives as the result of stated
 * conditions rather than out of nowhere.
 */
@Component({
  selector: 'plotted-home',
  standalone: true,
  imports: [RouterLink, AlertListComponent, HeroRouteComponent],
  template: `
    @if (auth.user(); as user) {
      <section class="hero">
        <!-- Renders nothing when there is nothing to say, which is the common
             case by design. A permanent "no alerts" panel would put the feature
             on screen every day for exactly the outcome it works hardest to
             produce. -->
        <plotted-alert-list />

        <div class="layout">
          <div class="copy">
            <dl class="telemetry coordinates">
              <div>
                <dt>Tonight</dt>
                <dd class="readout">{{ clock() }}</dd>
              </div>
              <div>
                <dt>Region</dt>
                <dd class="readout">{{ user.regionCode }}</dd>
              </div>
              <div>
                <dt>Traveller</dt>
                <dd>{{ user.displayName }}</dd>
              </div>
            </dl>

            <h1>Where are we going tonight?</h1>
            <p class="lede muted">
              One route through everything you pay for, ending at one thing to watch.
            </p>

            <a class="primary-action" routerLink="/tonight">
              Plot my night
              <span class="arrow" aria-hidden="true">&rarr;</span>
            </a>
          </div>

          <plotted-hero-route class="diagram" />
        </div>

        <!-- The other destinations, as waypoints rather than a link list. Grey
             throughout: none of these is the plotted choice, and the accent has
             to stay meaning that one thing. -->
        <nav class="waypoints" aria-label="Elsewhere in Plotted">
          <a routerLink="/search">
            <span class="waypoint" aria-hidden="true"></span>
            Search the catalogue
          </a>
          <a routerLink="/watchlist">
            <span class="waypoint" aria-hidden="true"></span>
            Your list
          </a>
          <a routerLink="/coverage">
            <span class="waypoint" aria-hidden="true"></span>
            What your services cover
          </a>
          <a routerLink="/plan">
            <span class="waypoint" aria-hidden="true"></span>
            Work out what to cancel
          </a>
          <a routerLink="/pilot">
            <span class="waypoint" aria-hidden="true"></span>
            Chart your taste
          </a>
        </nav>
      </section>
    }
  `,
  styles: `
    .hero {
      max-width: 62rem;
      margin-top: clamp(1rem, 5vh, 3rem);
    }

    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
      gap: 2.5rem;
      align-items: center;
    }

    /* The diagram is the argument, not an illustration of it, so it stacks
       above nothing and simply drops away when there is no room for it to be
       read at a glance. */
    @media (max-width: 54rem) {
      .layout {
        grid-template-columns: minmax(0, 1fr);
        gap: 1.5rem;
      }
    }

    .telemetry {
      display: flex;
      flex-wrap: wrap;
      gap: 0 1.75rem;
      margin: 0 0 1.25rem;

      div {
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
      }

      dt {
        font-size: 0.62rem;
        opacity: 0.7;
      }

      dd {
        margin: 0;
        font-size: 0.78rem;
        color: var(--plotted-text-muted);
        text-transform: none;
        letter-spacing: 0;
      }
    }

    h1 {
      margin: 0 0 0.6rem;
      font-size: clamp(1.75rem, 1.2rem + 2.2vw, 2.6rem);
    }

    .lede {
      font-size: 1.02rem;
      max-width: 26rem;
      margin-bottom: 1.75rem;
    }

    .primary-action {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.75rem 1.5rem;
      border-radius: 999px;
      background: var(--plotted-accent);
      color: var(--plotted-accent-ink);
      font-weight: 600;
      text-decoration: none;
      transition: background-color 0.15s ease;
    }

    .primary-action:hover {
      background: var(--plotted-accent-hover);
    }

    /* The arrow extends on hover, like a route being drawn a little further.
       Motion as meaning rather than flourish -- and it is 150ms, because this
       is a button somebody presses every evening. */
    .arrow {
      display: inline-block;
      transition: transform 0.15s ease;
    }

    .primary-action:hover .arrow {
      transform: translateX(3px);
    }

    .waypoints {
      margin-top: clamp(2rem, 6vh, 3.5rem);
      padding-top: 1.5rem;
      border-top: 1px solid var(--plotted-border);
      display: flex;
      flex-wrap: wrap;
      gap: 0.6rem 1.75rem;

      a {
        display: inline-flex;
        align-items: center;
        gap: 0.55rem;
        font-size: 0.85rem;
        color: var(--plotted-text-muted);
        text-decoration: none;
        transition: color 0.12s ease;
      }

      a:hover {
        color: var(--plotted-text);
      }

      /* The waypoint fills in on hover: the possibility becomes reachable. It
         does not turn orange, because hovering is not choosing. */
      a:hover .waypoint {
        border-color: var(--plotted-text);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .arrow,
      .primary-action {
        transition: none;
      }
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);

  /** A small touch, but this is an evening product and it should know that. */
  protected clock(): string {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}

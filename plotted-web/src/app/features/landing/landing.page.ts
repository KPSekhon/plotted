import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { messageFrom } from '../../core/error/problem-detail';
import { HeroRouteComponent } from '../../shared/map/hero-route.component';
import { PlottedIconComponent } from '../../shared/map/plotted-icon.component';

/**
 * What a signed-out visitor sees at `/`.
 *
 * Until now there was nothing here: `/` was guarded, so anyone arriving at the
 * deployed project was asked for credentials before being told what it was.
 * That is a wasted first impression, and for a project whose whole argument is
 * about respecting the visitor's time it was the wrong first move.
 *
 * ### Why the primary action is the demo rather than sign-up
 *
 * The strongest thing Plotted does is make one decision and explain it. A
 * signup form cannot show that; ninety seconds in Tonight Mode can. So the
 * button starts a throwaway account and goes straight to Tonight — the visitor
 * experiences the argument instead of reading it, and nobody has to type
 * anything to find out whether the product is interesting.
 *
 * ### Why it is short
 *
 * Four sections and two of them are one sentence. A landing page with eleven
 * scroll-jacked panels would contradict the product on the product's own front
 * door: Plotted exists because being shown too much is the problem.
 *
 * The hero animation lives here and *only* here. Replaying it on every sign-in
 * would turn a nice first impression into a delay someone sits through daily.
 */
@Component({
  selector: 'plotted-landing',
  standalone: true,
  imports: [RouterLink, HeroRouteComponent, PlottedIconComponent],
  template: `
    <div class="landing">
      <section class="hero">
        <div class="hero__copy">
          <p class="coordinates">Streaming decision engine &middot; Canada</p>
          <h1>Stop browsing.<br />Your night&rsquo;s plotted.</h1>
          <p class="lede">
            Pick what actually fits tonight, then work out which subscriptions are worth
            keeping. Plotted decides, and shows you why.
          </p>

          <div class="actions">
            <button type="button" class="primary" [disabled]="starting()" (click)="tryIt()">
              {{ starting() ? 'Plotting&hellip;' : 'Plot my night' }}
              <span class="arrow" aria-hidden="true">&rarr;</span>
            </button>
            <a class="secondary" routerLink="/sign-in">Sign in</a>
          </div>

          <p class="no-signup faint">
            No account needed. The demo gives you a throwaway one with a watchlist already on
            it, and it expires by itself.
          </p>

          @if (error(); as message) {
            <p class="form-error" role="alert">{{ message }}</p>
          }
        </div>

        <plotted-hero-route class="hero__diagram" />
      </section>

      <!-- Three claims, each with the shape of the thing it describes. No
           screenshots: the diagrams are the same primitives the real screens
           use, so the landing page cannot drift away from the product. -->
      <section class="pitch">
        <article>
          <p class="coordinates"><plotted-icon name="destination" [size]="16" /> Queue Theory</p>
          <h2>One choice. Two alternate routes.</h2>
          <p>
            Every other service answers &ldquo;what should I watch?&rdquo; with four hundred
            options. Plotted answers with one, sized to the time you actually have, on a service
            you already pay for &mdash; and every reason it gives is a real number from the
            ranking, never a sentence that sounds like one.
          </p>
        </article>

        <article>
          <p class="coordinates"><plotted-icon name="boundary" [size]="16" /> Cancel Culture</p>
          <h2>Your watchlist has a subscription problem.</h2>
          <p>
            A constraint solver plans which services to hold, month by month, against what is
            genuinely on your list. It will tell you what one more service would buy you, and
            when no plan fits your limits it says so rather than quietly relaxing one.
          </p>
        </article>

        <article>
          <p class="coordinates"><plotted-icon name="dead-end" [size]="16" /> The refusals</p>
          <h2>It tells you when it doesn&rsquo;t know.</h2>
          <p>
            Availability carries where it came from and when it was last checked. Stale prices
            disappear rather than being shown. Where the evidence is thin, Plotted says nothing
            instead of guessing &mdash; which is the part every recommender skips.
          </p>
        </article>
      </section>

      <section class="close">
        <h2>Other services recommend what they carry.</h2>
        <p class="lede">Plotted recommends what works for you.</p>
        <button type="button" class="primary" [disabled]="starting()" (click)="tryIt()">
          Plot my night
          <span class="arrow" aria-hidden="true">&rarr;</span>
        </button>
      </section>
    </div>
  `,
  styles: `
    .landing {
      display: flex;
      flex-direction: column;
      gap: clamp(3.5rem, 10vh, 6rem);
      padding-bottom: 2rem;
    }

    /* --- hero ----------------------------------------------------------- */

    .hero {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
      gap: 3rem;
      align-items: center;
      padding-top: clamp(1rem, 6vh, 4rem);
    }

    @media (max-width: 56rem) {
      .hero {
        grid-template-columns: minmax(0, 1fr);
        gap: 2rem;
      }
    }

    h1 {
      font-size: clamp(2.1rem, 1.4rem + 3.4vw, 3.4rem);
      margin: 0.5rem 0 1rem;
      letter-spacing: -0.03em;
    }

    .lede {
      font-size: 1.05rem;
      color: var(--plotted-text-muted);
      max-width: 30rem;
      margin: 0 0 1.75rem;
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 1.25rem;
    }

    .primary {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.8rem 1.6rem;
      border: 0;
      border-radius: 999px;
      background: var(--plotted-accent);
      color: var(--plotted-accent-ink);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
      transition: background-color 0.15s ease;
    }

    .primary:hover:not(:disabled) {
      background: var(--plotted-accent-hover);
    }

    .primary:disabled {
      opacity: 0.7;
      cursor: default;
    }

    .arrow {
      display: inline-block;
      transition: transform 0.15s ease;
    }

    .primary:hover:not(:disabled) .arrow {
      transform: translateX(3px);
    }

    .secondary {
      color: var(--plotted-text-muted);
      text-decoration: none;
      font-size: 0.92rem;
    }

    .secondary:hover {
      color: var(--plotted-text);
      text-decoration: underline;
      text-decoration-color: var(--plotted-accent);
    }

    .no-signup {
      margin: 1rem 0 0;
      font-size: 0.8rem;
      max-width: 26rem;
    }

    /* --- pitch ---------------------------------------------------------- */

    .pitch {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
      gap: 2rem 2.5rem;
      border-top: 1px solid var(--plotted-border);
      padding-top: clamp(2rem, 6vh, 3rem);
    }

    .pitch .coordinates {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      margin: 0 0 0.6rem;
      color: var(--plotted-text-faint);
    }

    .pitch h2 {
      font-size: 1.15rem;
      margin: 0 0 0.6rem;
    }

    .pitch p:last-child {
      margin: 0;
      font-size: 0.92rem;
      color: var(--plotted-text-muted);
    }

    /* --- close ---------------------------------------------------------- */

    .close {
      border-top: 1px solid var(--plotted-border);
      padding-top: clamp(2rem, 6vh, 3rem);
      display: grid;
      justify-items: start;
      gap: 0.4rem;
    }

    .close h2 {
      font-size: clamp(1.3rem, 1rem + 1.4vw, 1.9rem);
      color: var(--plotted-text-muted);
      margin: 0;
    }

    .close .lede {
      font-size: clamp(1.3rem, 1rem + 1.4vw, 1.9rem);
      color: var(--plotted-text);
      font-weight: 620;
      letter-spacing: -0.02em;
      margin: 0 0 1.25rem;
      max-width: none;
    }

    @media (prefers-reduced-motion: reduce) {
      .primary,
      .arrow {
        transition: none;
      }
    }
  `,
})
export class LandingPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly starting = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Straight to Tonight rather than to Home.
   *
   * Home is one button that says "plot my night", so landing there would ask a
   * visitor who has just pressed exactly that to press it again. The demo
   * account already has a watchlist, so Tonight has something real to answer
   * with immediately — which is the entire point of offering the demo.
   */
  protected tryIt(): void {
    this.starting.set(true);
    this.error.set(null);
    this.auth.startDemo().subscribe({
      next: () => {
        this.starting.set(false);
        void this.router.navigate(['/tonight']);
      },
      error: (failure: unknown) => {
        // Demo mode is off by default and 404s when it is, so this is a
        // reachable state on any deployment that has not enabled it. Say so
        // rather than leaving the button spinning.
        this.error.set(messageFrom(failure));
        this.starting.set(false);
      },
    });
  }
}

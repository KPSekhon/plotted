import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import {
  ACCESS_POLICY_LABELS,
  AccessPolicy,
  Pick,
  TonightResponse,
} from '../../core/tonight/tonight.models';
import { TonightService } from '../../core/tonight/tonight.service';
import { ContributionPlotComponent } from '../../shared/map/contribution-plot.component';
import { PlottedXComponent } from '../../shared/map/plotted-x.component';

/**
 * Tonight Mode — Queue Theory.
 *
 * Two things here are deliberately not the conventional choice. The empty answer
 * is presented as a *diagnosis* rather than an apology, because the constraints
 * were the request and quietly relaxing them would answer a different question.
 * And the reasons under each pick are the ranker's actual feature contributions,
 * so they cannot drift into plausible-sounding prose.
 *
 * ### Why the first pick is enormous and the others are not
 *
 * Three equal cards is a menu, and a menu is the problem Plotted exists to
 * solve — it hands the decision back at the exact moment it was supposed to make
 * it. So slot 1 gets the route, the X, the large poster and the explanation;
 * slots 2 and 3 are listed underneath as alternate routes, deliberately smaller.
 * The hierarchy is the product's argument rendered as layout: **Plotted chooses.
 * It does not dump options on you.**
 *
 * The backups still have to be *there*, because a recommender with no way to
 * disagree is one people stop trusting the first time it is wrong. They are
 * quieter, not hidden.
 */
@Component({
  selector: 'plotted-tonight',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    PlottedXComponent,
    ContributionPlotComponent,
  ],
  template: `
    <section class="page">
      <header>
        <p class="coordinates eyebrow-mono">Queue Theory / {{ clock() }}</p>
        <h1>What are we watching tonight?</h1>
        <p class="sub muted">
          One pick and two alternates, from your list, with the reasons behind them.
        </p>
      </header>

      <!-- The constraints, presented as the coordinates of the trip. Material
           keeps the actual controls: a hand-rolled select is where keyboard and
           screen-reader behaviour quietly goes wrong. -->
      <form class="controls" (ngSubmit)="ask()">
        <mat-form-field appearance="outline" class="minutes">
          <mat-label>Time you have</mat-label>
          <input matInput type="number" min="1" [(ngModel)]="minutes" name="minutes" />
          <span matTextSuffix>min</span>
          <mat-hint>Leave blank for no limit.</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="policy">
          <mat-label>What counts</mat-label>
          <mat-select [(ngModel)]="policy" name="policy">
            @for (option of policies; track option) {
              <mat-option [value]="option">{{ policyLabel(option) }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <button mat-flat-button type="submit" class="plot-button" [disabled]="loading()">
          {{ result() ? 'Replot' : 'Plot my night' }}
        </button>
      </form>

      @if (loading()) {
        <!-- Branded, because this wait is Plotted doing the thing it exists to
             do. Ordinary page loads keep ordinary spinners. -->
        <div class="plotting" role="status">
          <svg viewBox="0 0 240 12" fill="none" aria-hidden="true">
            <path class="drawing" d="M 4 8 C 70 8, 90 4, 150 4 L 224 4" stroke-linecap="round" />
          </svg>
          <p class="coordinates">Plotting your night&hellip;</p>
        </div>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else {
        <!-- Aliased one level in rather than on the @else: the block syntax
             only binds an "as" alias on a leading @if. -->
        @if (result(); as data) {
          @if (data.diagnosis; as diagnosis) {
          <!-- Not an error state. The request was answered; the answer is that
               nothing satisfies the constraints, and the counts say which one
               did the damage so the user knows which lever to pull. -->
          <section class="no-route" role="status">
            <plotted-x class="muted" [size]="30" />
            <h2>No route found.</h2>
            <p class="headline muted">{{ diagnosis.headline }}</p>

            @if (diagnosis.reasons.length > 0) {
              <ul class="blocks">
                @for (reason of diagnosis.reasons; track reason.reason) {
                  <li>
                    <span class="dead-end" aria-hidden="true"></span>
                    <span class="readout">{{ reason.count }}</span>
                    {{ reason.count === 1 ? 'title' : 'titles' }} &mdash;
                    {{ reason.explanation }}
                  </li>
                }
              </ul>
            }

            <p class="hint faint">
              Nothing was quietly relaxed to fill the space. Loosen a constraint, or
              <a routerLink="/watchlist">add more to your list</a>.
            </p>
          </section>
        } @else {
          <p class="basis coordinates">
            Plotted through {{ data.eligibleCount }} of {{ data.candidateCount }}
            {{ data.candidateCount === 1 ? 'title' : 'titles' }} on your list
          </p>

          @if (lead(); as pick) {
            <article class="lead">
              <div class="approach" aria-hidden="true">
                <plotted-x variant="route" [size]="30" />
              </div>

              <a class="poster" [routerLink]="['/titles', pick.titleId]">
                @if (pick.posterUrl) {
                  <img [src]="pick.posterUrl" [alt]="'Poster for ' + pick.name" />
                } @else {
                  <span class="poster-fallback" aria-hidden="true">
                    <mat-icon>movie</mat-icon>
                  </span>
                }
              </a>

              <div class="body">
                <p class="slot coordinates">
                  Destination
                  @if (pick.exploration) {
                    <!-- Surfaced rather than hidden: a deliberate wildcard
                         should say that it is one. -->
                    <span
                      class="wildcard"
                      matTooltip="Chosen at random rather than by score, so the ranker keeps learning"
                    >
                      wildcard
                    </span>
                  }
                </p>

                <h2>
                  <a [routerLink]="['/titles', pick.titleId]">{{ pick.name }}</a>
                </h2>

                <dl class="vitals coordinates">
                  <div>
                    <dt>Format</dt>
                    <dd>{{ pick.mediaType === 'movie' ? 'Film' : 'Series' }}</dd>
                  </div>
                  @if (pick.watchMinutes) {
                    <div>
                      <dt>Runtime</dt>
                      <dd class="readout">{{ formatMinutes(pick.watchMinutes) }}</dd>
                    </div>
                  }
                  @if (pick.availableOn.length > 0) {
                    <div>
                      <dt>Source</dt>
                      <dd>{{ pick.availableOn.join(', ') }}</dd>
                    </div>
                  }
                </dl>

                @if (pick.reasons.length > 0) {
                  <plotted-contribution-plot
                    class="why"
                    caption="Why this one"
                    [contributions]="pick.reasons"
                  />
                }

                <!-- The only way an acceptance ever gets recorded. Without this
                     control the decision log has what Plotted said and no
                     evidence anyone agreed, which is exactly half of the two
                     metrics that matter. -->
                @if (accepted() === pick.titleId) {
                  <p class="accepted">
                    <span class="plot-point" aria-hidden="true"></span>
                    Enjoy it.
                  </p>
                } @else {
                  <button
                    mat-flat-button
                    class="accept"
                    [disabled]="accepting() || accepted() !== null"
                    (click)="accept(data.requestId, pick.titleId)"
                  >
                    This is the one
                  </button>
                }
              </div>
            </article>
          }

          @if (alternates().length > 0) {
            <section class="alternates">
              <h3 class="coordinates">Alternate routes</h3>

              @for (pick of alternates(); track pick.titleId) {
                <article class="alternate">
                  <span class="waypoint" aria-hidden="true"></span>

                  <a class="poster" [routerLink]="['/titles', pick.titleId]">
                    @if (pick.posterUrl) {
                      <img [src]="pick.posterUrl" [alt]="'Poster for ' + pick.name" loading="lazy" />
                    } @else {
                      <span class="poster-fallback" aria-hidden="true">
                        <mat-icon>movie</mat-icon>
                      </span>
                    }
                  </a>

                  <div class="body">
                    <h4>
                      <a [routerLink]="['/titles', pick.titleId]">{{ pick.name }}</a>
                      @if (pick.exploration) {
                        <span
                          class="wildcard"
                          matTooltip="Chosen at random rather than by score, so the ranker keeps learning"
                        >
                          wildcard
                        </span>
                      }
                    </h4>

                    <p class="meta coordinates">
                      <span>{{ pick.mediaType === 'movie' ? 'Film' : 'Series' }}</span>
                      @if (pick.watchMinutes) {
                        <span class="readout">{{ formatMinutes(pick.watchMinutes) }}</span>
                      }
                      @if (pick.availableOn.length > 0) {
                        <span>{{ pick.availableOn.join(', ') }}</span>
                      }
                    </p>

                    @if (accepted() === pick.titleId) {
                      <p class="accepted">
                        <span class="plot-point" aria-hidden="true"></span>
                        Enjoy it.
                      </p>
                    } @else {
                      <button
                        mat-stroked-button
                        class="accept accept--small"
                        [disabled]="accepting() || accepted() !== null"
                        (click)="accept(data.requestId, pick.titleId)"
                      >
                        Take this route
                      </button>
                    }
                  </div>
                </article>
              }
            </section>
          }
          }
        }
      }
    </section>
  `,
  styles: `
    .page {
      max-width: 58rem;
      margin: 0 auto;
      padding: 1.5rem 1rem 4rem;
    }

    .eyebrow-mono {
      margin: 0 0 0.4rem;
    }

    h1 {
      margin: 0 0 0.3rem;
      font-size: clamp(1.5rem, 1.1rem + 1.8vw, 2.1rem);
    }

    .sub {
      margin: 0 0 1.75rem;
      font-size: 0.9rem;
    }

    .controls {
      display: flex;
      gap: 1rem 0.9rem;
      align-items: flex-start;
      flex-wrap: wrap;
      margin-bottom: 2rem;
    }

    /* Wide enough for what is actually in them. At 10rem the hint "Leave blank
       for no limit." wrapped under a field that also carries a "min" suffix,
       and at 14rem the longest option -- "Only what I pay for" -- crowded the
       select's dropdown arrow. Sized to the content, bounded so neither
       sprawls. */
    .minutes {
      flex: 1 1 13rem;
      min-width: 13rem;
      max-width: 16rem;
    }

    .policy {
      flex: 1 1 17rem;
      min-width: 17rem;
      max-width: 22rem;
    }

    @media (max-width: 32rem) {
      .minutes,
      .policy {
        flex-basis: 100%;
        max-width: none;
      }
    }

    .plot-button {
      margin-top: 0.35rem;
    }

    /* --- the wait ------------------------------------------------------- */

    .plotting {
      display: grid;
      justify-items: center;
      gap: 0.5rem;
      padding: 3rem 0;

      svg {
        width: min(20rem, 100%);
      }
    }

    .drawing {
      stroke: var(--plotted-accent);
      stroke-width: 2;
      stroke-dasharray: 240;
      --draw-length: 240;
      animation: plotted-draw 1200ms ease-in-out infinite;
    }

    @media (prefers-reduced-motion: reduce) {
      .drawing {
        animation: none;
        stroke-dashoffset: 0;
      }
    }

    .basis {
      margin: 0 0 1rem;
    }

    /* --- the answer ----------------------------------------------------- */

    .lead {
      display: grid;
      grid-template-columns: auto 12rem minmax(0, 1fr);
      gap: 1.5rem;
      align-items: center;
      padding: 1.5rem;
      border: 1px solid var(--plotted-border);
      border-radius: var(--plotted-radius);
      background: var(--plotted-surface);
    }

    /* The approach is the one piece here that is purely graphical, so it is the
       first thing to go when space is short -- ahead of anything carrying
       information. */
    @media (max-width: 46rem) {
      .lead {
        grid-template-columns: 7rem minmax(0, 1fr);
        gap: 1rem;
        padding: 1rem;
      }

      .approach {
        display: none;
      }
    }

    .poster {
      display: block;
      line-height: 0;
      border-radius: var(--plotted-radius-sm);
      overflow: hidden;
    }

    /* Posters are the only colour on the page besides the accent, which is what
       makes them read as destinations. Never tinted. */
    .poster img,
    .poster-fallback {
      width: 100%;
      aspect-ratio: 2 / 3;
      object-fit: cover;
      display: block;
    }

    .poster-fallback {
      display: grid;
      place-items: center;
      background: var(--plotted-surface-raised);
      color: var(--plotted-text-faint);
    }

    .slot {
      margin: 0 0 0.25rem;
      color: var(--plotted-accent);
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }

    .lead h2 {
      margin: 0 0 0.75rem;
      font-size: clamp(1.25rem, 1rem + 1.2vw, 1.75rem);

      a {
        color: inherit;
        text-decoration: none;
      }

      a:hover {
        text-decoration: underline;
        text-decoration-color: var(--plotted-accent);
      }
    }

    .vitals {
      display: flex;
      flex-wrap: wrap;
      gap: 0.3rem 1.5rem;
      margin: 0 0 1rem;

      div {
        display: flex;
        flex-direction: column;
        gap: 0.05rem;
      }

      dt {
        font-size: 0.6rem;
        opacity: 0.7;
      }

      dd {
        margin: 0;
        font-size: 0.78rem;
        color: var(--plotted-text-muted);
        letter-spacing: 0;
        text-transform: none;
      }
    }

    .why {
      display: block;
      margin-bottom: 1.1rem;
    }

    .wildcard {
      text-transform: none;
      letter-spacing: 0;
      font-family: Geist, Inter, system-ui, sans-serif;
      font-weight: 600;
      font-size: 0.68rem;
      padding: 0.1rem 0.4rem;
      border-radius: 999px;
      background: var(--plotted-surface-raised);
      color: var(--plotted-text-faint);
      cursor: help;
    }

    .accepted {
      margin: 0;
      font-size: 0.85rem;
      color: var(--plotted-text-muted);
      display: flex;
      gap: 0.45rem;
      align-items: center;
    }

    /* --- the alternates ------------------------------------------------- */

    .alternates {
      margin-top: 2.5rem;

      h3 {
        margin: 0 0 0.9rem;
        font-size: 0.7rem;
        font-weight: 500;
      }
    }

    .alternate {
      display: grid;
      grid-template-columns: auto 4rem minmax(0, 1fr);
      gap: 0.9rem;
      align-items: center;
      padding: 0.75rem 0;
      border-top: 1px solid var(--plotted-border);

      h4 {
        margin: 0 0 0.15rem;
        font-size: 0.95rem;
        font-weight: 600;
        display: flex;
        gap: 0.5rem;
        align-items: center;
        flex-wrap: wrap;

        a {
          color: inherit;
          text-decoration: none;
        }

        a:hover {
          text-decoration: underline;
        }
      }
    }

    .alternate .meta {
      margin: 0 0 0.5rem;
      display: flex;
      flex-wrap: wrap;
      gap: 0.2rem 1rem;
    }

    .accept--small {
      font-size: 0.8rem;
    }

    /* --- no route ------------------------------------------------------- */

    .no-route {
      border: 1px dashed var(--plotted-border-strong);
      border-radius: var(--plotted-radius);
      padding: 2.5rem 1.5rem;
      text-align: center;

      h2 {
        margin: 0.75rem 0 0.4rem;
        font-size: 1.15rem;
      }
    }

    .no-route .headline {
      margin: 0 auto 1.25rem;
      max-width: 34rem;
      font-size: 0.9rem;
    }

    .blocks {
      list-style: none;
      padding: 0;
      margin: 0 auto 1.25rem;
      display: inline-grid;
      gap: 0.4rem;
      text-align: left;
      font-size: 0.85rem;
      color: var(--plotted-text-muted);

      li {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
    }

    .hint {
      font-size: 0.8rem;
      margin: 0;
    }

    .error {
      color: var(--plotted-danger);
      font-size: 0.88rem;
    }
  `,
})
export class TonightPage {
  private readonly tonight = inject(TonightService);

  protected readonly result = signal<TonightResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The title accepted from the current answer, or null. One decision per set of picks. */
  protected readonly accepted = signal<string | null>(null);
  protected readonly accepting = signal(false);

  protected readonly policies: readonly AccessPolicy[] = [
    'active_subscriptions_only',
    'include_free',
    'any_subscription',
  ];

  protected minutes: number | null = null;
  protected policy: AccessPolicy = 'active_subscriptions_only';

  /**
   * Slot 1, found by position rather than by index.
   *
   * The API's contract is that position 1 is the pick, and reading `picks[0]`
   * would quietly promote a backup to the hero treatment if the order ever
   * arrived differently. This screen makes one recommendation enormous, so it
   * had better be the one the ranker actually chose.
   */
  protected lead(): Pick | null {
    return this.result()?.picks.find((pick) => pick.position === 1) ?? null;
  }

  protected alternates(): readonly Pick[] {
    return (this.result()?.picks ?? []).filter((pick) => pick.position !== 1);
  }

  protected clock(): string {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  protected policyLabel(policy: AccessPolicy): string {
    return ACCESS_POLICY_LABELS[policy];
  }

  protected formatMinutes(minutes: number): string {
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }

  protected ask(): void {
    this.loading.set(true);
    this.error.set(null);
    // A new question is a new decision, so the previous answer must not carry
    // over — otherwise the button would sit disabled on a set of picks nobody
    // has chosen from.
    this.accepted.set(null);
    // Blank means no limit, so it must reach the service as null rather than 0 —
    // zero would be a claim of having no time and would filter out everything.
    const budget = this.minutes && this.minutes > 0 ? this.minutes : null;
    this.tonight.recommend(budget, this.policy).subscribe({
      next: (response) => {
        this.result.set(response);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.loading.set(false);
      },
    });
  }

  /**
   * Records which pick the user is actually watching.
   *
   * Set from the response rather than optimistically, for the same reason the
   * add-to-list button is: this is the only evidence the decision log will ever
   * have that somebody agreed, and marking it locally when the server did not
   * record it would put a gap in the one measurement that matters.
   */
  protected accept(requestId: string, titleId: string): void {
    this.accepting.set(true);
    this.error.set(null);
    this.tonight.accept(requestId, { titleId }).subscribe({
      next: () => {
        this.accepted.set(titleId);
        this.accepting.set(false);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.accepting.set(false);
      },
    });
  }
}

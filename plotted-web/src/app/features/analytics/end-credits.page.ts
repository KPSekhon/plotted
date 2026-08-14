import { Component, inject, signal } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { EndCredits } from '../../core/analytics/analytics.models';
import { AnalyticsService } from '../../core/analytics/analytics.service';
import { messageFrom } from '../../core/error/problem-detail';
import { DemoNoteComponent } from '../../shared/map/demo-note.component';
import { EmptyStateComponent } from '../../shared/map/empty-state.component';

/**
 * End Credits: whether any of this actually helped.
 *
 * Two numbers, and the screen's whole job is to keep them honest. Both are
 * easy to render in a flattering way and the API has already refused to
 * compute them that way; the interface must not undo that by filling a null
 * with a zero or by showing a rate without the sample it rests on.
 *
 * ### Why nulls are rendered as sentences
 *
 * A dash, an em-dash or a greyed-out zero all read as "nothing happened". The
 * actual meaning is narrower and more interesting — *we have not been given
 * enough evidence to say* — and that is a claim about the log, not about the
 * product. So an absent metric gets a sentence explaining which condition was
 * not met, and never a number.
 *
 * ### Why the excluded counts are as prominent as the metrics
 *
 * `excludedAsStale` and `tooRecentToJudge` are the two rules that stop these
 * figures drifting upward on their own. A reader who cannot see them has to
 * take the headline on trust, and this is a screen specifically about not
 * doing that.
 */
@Component({
  selector: 'plotted-end-credits',
  standalone: true,
  imports: [RouterLink, MatProgressSpinnerModule, EmptyStateComponent, DemoNoteComponent],
  template: `
    <section class="page">
      <header>
        <p class="coordinates eyebrow-mono">End Credits &middot; {{ month() }}</p>
        <h1>Did any of this help?</h1>
        <p class="sub muted">
          Two questions decide whether Plotted works: does it save you time, and did you watch
          the thing. Everything else an analytics screen could show is decoration.
        </p>
      </header>

      @if (loading()) {
        <div class="centre"><mat-spinner diameter="36" /></div>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else {
        <!-- Aliased one level in: the block syntax only binds an "as" alias on
             a leading @if, never on an @else if. -->
        @if (credits(); as data) {
        @if (data.recommendationsServed === 0) {
          <plotted-empty-state heading="No decisions logged yet.">
            <p>
              End Credits measures what happened after Plotted answered. Ask it for something and
              this fills in.
            </p>
            <div class="actions">
              <a class="cta" routerLink="/tonight">Plot my night</a>
            </div>
          </plotted-empty-state>
        } @else {
          <div class="metrics">
            <!-- Decision latency ------------------------------------------- -->
            <article class="metric">
              <p class="coordinates label">Decision latency</p>

              @if (data.decisionLatency.medianSeconds !== null) {
                <p class="figure readout">{{ duration(data.decisionLatency.medianSeconds) }}</p>
                <p class="caption">
                  median, from
                  <strong>{{ data.decisionLatency.sampleSize }}</strong>
                  {{ data.decisionLatency.sampleSize === 1 ? 'decision' : 'decisions' }}
                </p>

                <dl class="detail coordinates">
                  <div>
                    <dt>Fastest</dt>
                    <dd class="readout">{{ duration(data.decisionLatency.fastestSeconds) }}</dd>
                  </div>
                  <div>
                    <dt>Slowest</dt>
                    <dd class="readout">{{ duration(data.decisionLatency.slowestSeconds) }}</dd>
                  </div>
                </dl>

                <p class="rule">
                  The median rather than the mean, because wall-clock has an unbounded tail: one
                  tab left open all afternoon moves a mean permanently and a median not at all.
                </p>

                @if (data.decisionLatency.excludedAsStale > 0) {
                  <p class="excluded">
                    <span class="readout">{{ data.decisionLatency.excludedAsStale }}</span>
                    {{ data.decisionLatency.excludedAsStale === 1 ? 'acceptance' : 'acceptances' }}
                    arrived more than four hours later and
                    {{ data.decisionLatency.excludedAsStale === 1 ? 'was' : 'were' }} left out
                    &mdash; that is a different session, not a long deliberation.
                  </p>
                }
              } @else {
                <p class="unknown">Nothing accepted yet, so there is no decision to time.</p>
              }
            </article>

            <!-- Completion --------------------------------------------------- -->
            <article class="metric">
              <p class="coordinates label">Accepted and completed</p>

              @if (data.acceptedAndCompleted.rate !== null) {
                <p class="figure readout">{{ percent(data.acceptedAndCompleted.rate) }}</p>
                <p class="caption">
                  <strong>{{ data.acceptedAndCompleted.completed }}</strong>
                  of
                  <strong>{{ data.acceptedAndCompleted.judged }}</strong>
                  finished
                </p>

                <!-- A bar rather than a route: this is a proportion, and a
                     proportion is not a journey. Orange marks the completed
                     share because that is the outcome Plotted was aiming at. -->
                <div class="bar" aria-hidden="true">
                  <span class="done" [style.width.%]="data.acceptedAndCompleted.rate * 100"></span>
                </div>

                <p class="rule">
                  Acceptance alone cannot tell a persuasive recommender from a correct one. This
                  is the number that can.
                </p>

                @if (data.acceptedAndCompleted.tooRecentToJudge > 0) {
                  <p class="excluded">
                    <span class="readout">{{ data.acceptedAndCompleted.tooRecentToJudge }}</span>
                    accepted in the last fortnight and held back. Counting those as failures would
                    make this rate climb on its own as the log aged, which looks exactly like the
                    product improving.
                  </p>
                }
              } @else {
                <p class="unknown">
                  Nothing has been accepted long enough ago to judge. A rate of 0% would be
                  evidence of failure, and there is none &mdash; only an absence of evidence.
                </p>
              }
            </article>
          </div>

          <section class="basis">
            <h2 class="coordinates">What it was asked</h2>
            <dl class="counts">
              <div>
                <dt>Recommendations served</dt>
                <dd class="readout">{{ data.recommendationsServed }}</dd>
              </div>
              <div>
                <dt>Answered with nothing</dt>
                <dd class="readout">{{ data.nothingFitCount }}</dd>
              </div>
            </dl>
            <p class="rule">
              Refusals are reported beside the rest rather than hidden. A recommender that never
              says no is not being careful, and the count is how you would notice if Plotted
              started quietly relaxing your constraints.
            </p>
          </section>

          <!-- Both figures above are computed exactly as they would be for a
               real account; the log they read is the manufactured one. Saying
               so here rather than in the chrome alone, because this is the
               screen where an unlabelled number would be a measurement claim
               about a product nobody has used. -->
          <plotted-demo-note>
            Both figures are computed from generated demo activity, not from decisions anyone
            made. The rules behind them are the real ones.
          </plotted-demo-note>
        }
        }
      }
    </section>
  `,
  styles: `
    .page {
      max-width: 52rem;
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
      margin: 0 0 2.25rem;
      font-size: 0.9rem;
      max-width: 38rem;
    }

    .metrics {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(17rem, 1fr));
      gap: 1rem;
      margin-bottom: 2rem;
    }

    .metric {
      border: 1px solid var(--plotted-border);
      border-radius: var(--plotted-radius);
      background: var(--plotted-surface);
      padding: 1.25rem 1.4rem;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }

    .label {
      margin: 0;
    }

    /* Neutral, not orange. These are measurements of the product, not the
       product choosing something — and the accent has exactly one meaning. */
    .figure {
      margin: 0.2rem 0 0;
      font-size: clamp(2rem, 1.5rem + 2.4vw, 2.9rem);
      font-weight: 600;
      letter-spacing: -0.03em;
      line-height: 1;
      color: var(--plotted-text);
    }

    .caption {
      margin: 0 0 0.5rem;
      font-size: 0.82rem;
      color: var(--plotted-text-muted);

      strong {
        color: var(--plotted-text);
        font-weight: 600;
      }
    }

    .detail {
      display: flex;
      gap: 1.75rem;
      margin: 0.35rem 0 0.5rem;

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
        font-size: 0.8rem;
        color: var(--plotted-text-muted);
        letter-spacing: 0;
        text-transform: none;
      }
    }

    .bar {
      height: 3px;
      border-radius: 2px;
      background: var(--plotted-border);
      overflow: hidden;
      margin: 0.5rem 0;

      .done {
        display: block;
        height: 100%;
        background: var(--plotted-accent);
      }
    }

    .rule {
      margin: 0.35rem 0 0;
      font-size: 0.78rem;
      color: var(--plotted-text-faint);
    }

    /* As prominent as the figure it qualifies. These two counts are the rules
       that stop the headline drifting upward on its own, and a reader who
       cannot see them is taking it on trust. */
    .excluded {
      margin: 0.75rem 0 0;
      padding-top: 0.75rem;
      border-top: 1px dashed var(--plotted-border-strong);
      font-size: 0.78rem;
      color: var(--plotted-text-muted);
    }

    .unknown {
      margin: 0.5rem 0 0;
      font-size: 0.85rem;
      color: var(--plotted-text-muted);
    }

    .basis {
      border-top: 1px solid var(--plotted-border);
      padding-top: 1.5rem;

      h2 {
        margin: 0 0 0.9rem;
        font-size: 0.68rem;
        font-weight: 500;
      }
    }

    .counts {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem 3rem;
      margin: 0 0 0.75rem;

      div {
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
      }

      dt {
        font-size: 0.72rem;
        color: var(--plotted-text-faint);
      }

      dd {
        margin: 0;
        font-size: 1.4rem;
        font-weight: 600;
        letter-spacing: -0.02em;
      }
    }

    .cta {
      display: inline-flex;
      align-items: center;
      padding: 0.6rem 1.25rem;
      border-radius: 999px;
      background: var(--plotted-accent);
      color: var(--plotted-accent-ink);
      font-weight: 600;
      text-decoration: none;
    }

    .centre {
      display: grid;
      place-items: center;
      padding: 3rem;
    }

    .error {
      color: var(--plotted-danger);
      font-size: 0.88rem;
    }
  `,
})
export class EndCreditsPage {
  private readonly analytics = inject(AnalyticsService);

  protected readonly credits = signal<EndCredits | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.analytics.endCredits().subscribe({
      next: (credits) => {
        this.credits.set(credits);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.loading.set(false);
      },
    });
  }

  protected month(): string {
    return new Date().toLocaleDateString([], { month: 'long', year: 'numeric' });
  }

  protected percent(rate: number): string {
    return `${Math.round(rate * 100)}%`;
  }

  /**
   * Seconds as something a person reads.
   *
   * Deliberately not "0s" for a null. The caller checks for null before
   * rendering a figure; this only ever formats a number that exists, and the
   * fallback is a dash rather than a zero so a mistake shows up as obviously
   * missing rather than as an implausibly good result.
   */
  protected duration(seconds: number | null): string {
    if (seconds === null) return '—';
    if (seconds < 60) return `${seconds}s`;

    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;
    if (minutes < 60) return rest === 0 ? `${minutes}m` : `${minutes}m ${rest}s`;

    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m`;
  }
}

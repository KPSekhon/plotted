import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import {
  ACCESS_POLICY_LABELS,
  AccessPolicy,
  TonightResponse,
} from '../../core/tonight/tonight.models';
import { TonightService } from '../../core/tonight/tonight.service';

/**
 * Tonight Mode.
 *
 * Two things here are deliberately not the conventional choice. The empty answer
 * is presented as a *diagnosis* rather than an apology, because the constraints
 * were the request and quietly relaxing them would answer a different question.
 * And the reasons under each pick are the ranker's actual feature contributions,
 * so they cannot drift into plausible-sounding prose.
 */
@Component({
  selector: 'plotted-tonight',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  template: `
    <section class="page">
      <header>
        <h1>Tonight</h1>
        <p class="sub">
          One pick and two backups, from your list, with the reasons behind
          them.
        </p>
      </header>

      <form class="controls" (ngSubmit)="ask()">
        <mat-form-field appearance="outline" class="minutes">
          <mat-label>Time you have</mat-label>
          <input
            matInput
            type="number"
            min="1"
            [(ngModel)]="minutes"
            name="minutes"
          />
          <span matTextSuffix>min</span>
          <mat-hint>Leave blank for no limit.</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="policy">
          <mat-label>What counts</mat-label>
          <mat-select [(ngModel)]="policy" name="policy">
            @for (option of policies; track option) {
              <mat-option [value]="option">{{
                policyLabel(option)
              }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <button mat-flat-button type="submit" [disabled]="loading()">
          Find something
        </button>
      </form>

      @if (loading()) {
        <div class="centre"><mat-spinner diameter="36" /></div>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else {
        @if (result(); as data) {
          @if (data.diagnosis; as diagnosis) {
            <!-- Not an error state. The request was answered; the answer is that
               nothing satisfies the constraints, and the counts say which one
               did the damage so the user knows which lever to pull. -->
            <section class="diagnosis" role="status">
              <mat-icon>filter_alt_off</mat-icon>
              <h2>{{ diagnosis.headline }}</h2>
              @if (diagnosis.reasons.length > 0) {
                <ul>
                  @for (reason of diagnosis.reasons; track reason.reason) {
                    <li>
                      <strong>{{ reason.count }}</strong>
                      {{ reason.count === 1 ? 'title' : 'titles' }} —
                      {{ reason.explanation }}
                    </li>
                  }
                </ul>
              }
              <p class="hint">
                Nothing was quietly relaxed to fill the space. Change what you
                asked for, or
                <a routerLink="/watchlist">add more to your list</a>.
              </p>
            </section>
          } @else {
            <p class="basis">
              Chose from {{ data.eligibleCount }} of {{ data.candidateCount }}
              {{ data.candidateCount === 1 ? 'title' : 'titles' }} on your list.
            </p>

            <div class="picks">
              @for (pick of data.picks; track pick.titleId) {
                <article class="pick" [class.lead]="pick.position === 1">
                  <div class="poster">
                    @if (pick.posterUrl) {
                      <img
                        [src]="pick.posterUrl"
                        [alt]="'Poster for ' + pick.name"
                        loading="lazy"
                      />
                    } @else {
                      <div class="poster-fallback" aria-hidden="true">
                        <mat-icon>movie</mat-icon>
                      </div>
                    }
                  </div>

                  <div class="body">
                    <p class="slot">
                      {{
                        pick.position === 1
                          ? 'Tonight'
                          : 'Backup ' + (pick.position - 1)
                      }}
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
                      <a [routerLink]="['/titles', pick.titleId]">{{
                        pick.name
                      }}</a>
                    </h2>

                    <p class="meta">
                      <span>{{
                        pick.mediaType === 'movie' ? 'Film' : 'Series'
                      }}</span>
                      @if (pick.watchMinutes) {
                        <span
                          >&middot; {{ formatMinutes(pick.watchMinutes) }}</span
                        >
                      }
                      @if (pick.availableOn.length > 0) {
                        <span>&middot; {{ pick.availableOn.join(', ') }}</span>
                      }
                    </p>

                    @if (pick.reasons.length > 0) {
                      <ul class="reasons">
                        @for (reason of pick.reasons; track reason.reason) {
                          <li>
                            <span class="share" aria-hidden="true">
                              <span [style.width.%]="reason.share * 100"></span>
                            </span>
                            {{ reason.reason }}
                          </li>
                        }
                      </ul>
                    }

                    <!-- The only way an acceptance ever gets recorded. Without
                         this control the decision log has what Plotted said and
                         no evidence anyone agreed, which is exactly half of the
                         two metrics that matter. -->
                    @if (accepted() === pick.titleId) {
                      <p class="accepted">
                        <mat-icon inline>check</mat-icon>
                        Enjoy it.
                      </p>
                    } @else {
                      <button
                        mat-stroked-button
                        class="accept"
                        [disabled]="accepting() || accepted() !== null"
                        (click)="accept(data.requestId, pick.titleId)"
                      >
                        Watching this
                      </button>
                    }
                  </div>
                </article>
              }
            </div>
          }
        }
      }
    </section>
  `,
  styles: `
    .page {
      max-width: 50rem;
      margin: 0 auto;
      padding: 1.5rem 1rem 3rem;
    }

    .accept {
      margin-top: 0.75rem;
    }

    .accepted {
      margin: 0.75rem 0 0;
      font-size: 0.85rem;
      color: var(--plotted-text-faint);
      display: flex;
      gap: 0.3rem;
      align-items: baseline;
    }

    h1 {
      margin: 0 0 0.25rem;
      font-size: 1.6rem;
      letter-spacing: -0.02em;
    }

    .sub {
      margin: 0 0 1.5rem;
      color: var(--plotted-text-faint);
      font-size: 0.85rem;
    }

    .controls {
      display: flex;
      gap: 1rem 0.9rem;
      align-items: flex-start;
      flex-wrap: wrap;
      margin-bottom: 1.5rem;
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

    .basis {
      font-size: 0.8rem;
      color: var(--plotted-text-faint);
      margin: 0 0 0.9rem;
    }

    .pick {
      display: grid;
      grid-template-columns: 5rem 1fr;
      gap: 1rem;
      padding: 0.9rem;
      border: 1px solid var(--plotted-border);
      border-radius: 12px;
      background: var(--plotted-surface);
      margin-bottom: 0.75rem;
    }

    /* The pick is the answer; the backups are the menu. */
    .pick.lead {
      border-color: var(--plotted-accent);
      grid-template-columns: 7.5rem 1fr;
    }

    img,
    .poster-fallback {
      width: 100%;
      aspect-ratio: 2 / 3;
      object-fit: cover;
      border-radius: 6px;
      display: block;
    }

    .poster-fallback {
      display: grid;
      place-items: center;
      background: var(--plotted-surface-raised);
      color: var(--plotted-text-faint);
    }

    .slot {
      margin: 0 0 0.15rem;
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.09em;
      font-weight: 700;
      color: var(--plotted-accent);
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }

    .wildcard {
      text-transform: none;
      letter-spacing: 0;
      font-weight: 600;
      font-size: 0.68rem;
      padding: 0.1rem 0.4rem;
      border-radius: 999px;
      background: var(--plotted-surface-raised);
      color: var(--plotted-text-faint);
      cursor: help;
    }

    .body h2 {
      margin: 0 0 0.2rem;
      font-size: 1.05rem;

      a {
        color: inherit;
        text-decoration: none;
      }

      a:hover {
        text-decoration: underline;
      }
    }

    .meta {
      margin: 0 0 0.6rem;
      font-size: 0.76rem;
      color: var(--plotted-text-faint);
      display: flex;
      gap: 0.25rem;
      flex-wrap: wrap;
    }

    .reasons {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 0.3rem;

      li {
        display: flex;
        align-items: center;
        gap: 0.55rem;
        font-size: 0.78rem;
        color: var(--plotted-text-faint);
      }
    }

    .share {
      display: block;
      width: 3.5rem;
      height: 4px;
      border-radius: 2px;
      background: var(--plotted-surface-raised);
      overflow: hidden;
      flex-shrink: 0;

      span {
        display: block;
        height: 100%;
        background: var(--plotted-accent);
      }
    }

    .diagnosis {
      border: 1px dashed var(--plotted-border);
      border-radius: 12px;
      padding: 2rem 1.5rem;
      text-align: center;

      mat-icon {
        font-size: 2.2rem;
        width: 2.2rem;
        height: 2.2rem;
        color: var(--plotted-text-faint);
      }

      h2 {
        margin: 0.6rem 0 0.9rem;
        font-size: 1.05rem;
        font-weight: 600;
      }

      ul {
        list-style: none;
        padding: 0;
        margin: 0 auto 1rem;
        display: inline-grid;
        gap: 0.3rem;
        text-align: left;
        font-size: 0.85rem;
        color: var(--plotted-text-faint);
      }
    }

    .hint {
      font-size: 0.8rem;
      color: var(--plotted-text-faint);
      margin: 0;
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

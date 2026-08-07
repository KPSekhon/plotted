import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import { PilotState, PreferenceProfile } from '../../core/pilot/pilot.models';
import { PilotService } from '../../core/pilot/pilot.service';

/**
 * Pilot Season: fifteen "which of these two?" questions.
 *
 * Three things here are deliberate rather than incidental.
 *
 * **Skip is a first-class answer**, given the same visual weight as the two
 * titles. A forced choice between two films somebody has not seen is a coin flip
 * recorded as evidence, and a skip button hidden in the corner is a skip button
 * nobody presses.
 *
 * **The profile reports what it could not find.** Axes with no verdict are shown
 * alongside the ones with, because "we never asked about this" and "we asked and
 * you were balanced" are different facts and both are part of the answer.
 *
 * **Running out of questions is not the same as finishing.** An exhausted ladder
 * means the catalogue is too thin to contrast, and saying "all done" instead
 * would hide a real problem behind a congratulation.
 */
@Component({
  selector: 'plotted-pilot',
  standalone: true,
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <section class="page">
      <header>
        <h1>Pilot Season</h1>
        <p class="sub">
          Fifteen quick choices. Plotted turns them into a taste profile — and
          tells you which parts of it are worth trusting.
        </p>
      </header>

      @if (loading()) {
        <mat-spinner diameter="32" aria-label="Loading" />
      }

      @if (error(); as message) {
        <p class="form-error" role="alert">{{ message }}</p>
      }

      @if (state(); as current) {
        @if (current.question; as question) {
          <p class="progress-line">
            Question {{ question.position }} of {{ current.total }}
            <span class="axis">&middot; {{ question.axisLabel }}</span>
          </p>
          <mat-progress-bar
            mode="determinate"
            [value]="(current.answered + current.skipped) / current.total * 100"
            aria-label="Progress"
          />

          <p class="prompt">Which would you rather watch?</p>

          <div class="pair">
            @for (option of [question.left, question.right]; track option.titleId) {
              <button
                class="option"
                type="button"
                [disabled]="busy()"
                (click)="choose(option.titleId)"
              >
                @if (option.posterUrl) {
                  <img [src]="option.posterUrl" [alt]="'Poster for ' + option.name" />
                } @else {
                  <span class="poster-fallback" aria-hidden="true"><mat-icon>movie</mat-icon></span>
                }
                <span class="name">{{ option.name }}</span>
                <span class="meta">
                  {{ option.mediaType === 'movie' ? 'Film' : 'Series' }}
                  @if (option.releaseYear) {
                    &middot; {{ option.releaseYear }}
                  }
                </span>
              </button>
            }
          </div>

          <!-- Given the same weight as the two options on purpose. A forced
               choice between two titles you have not seen is a coin flip, and a
               coin flip recorded as a preference is worse than a shorter
               questionnaire. -->
          <button mat-stroked-button class="skip" [disabled]="busy()" (click)="skip()">
            <mat-icon>skip_next</mat-icon>
            Haven't seen either
          </button>
          <p class="skip-note">Skipping records nothing about your taste. It just moves on.</p>
        } @else if (current.exhausted) {
          <!-- Not "all done". The ladder ran out of pairs that contrast enough
               to be worth asking about, which is a thin catalogue rather than a
               finished questionnaire. -->
          <div class="ended">
            <h2>That is every question this catalogue can support</h2>
            <p>
              Plotted asks about pairs that differ clearly on one axis and little
              else. With {{ current.answered }} answered
              @if (current.skipped > 0) {
                and {{ current.skipped }} skipped
              }
              it has run out of pairs that qualify — the catalogue is too small or
              too uniform to contrast further. More titles would mean more
              questions.
            </p>
          </div>
        } @else {
          <div class="ended">
            <h2>Done</h2>
            <p>
              {{ current.answered }} answered
              @if (current.skipped > 0) {
                , {{ current.skipped }} skipped
              }
              .
            </p>
          </div>
        }
      }

      @if (profile(); as fitted) {
        <section class="profile" aria-label="Your taste profile">
          <h2>What that says</h2>

          @if (!fitted.informative) {
            <p class="degraded">
              Nothing here is strong enough to act on yet. That is a real answer
              rather than a failure — ranking will ignore this profile rather than
              use a number computed from noise.
            </p>
          }

          <ul class="axes">
            @for (axis of fitted.axes; track axis.axis) {
              <li [class.unstated]="!axis.stated">
                <span class="label">{{ axis.label }}</span>
                <span class="sentence">{{ axis.sentence }}</span>
                <span class="verdict">{{ verdictLabel(axis.verdict) }}</span>
              </li>
            }
          </ul>

          <p class="footnote">
            Fitted from {{ fitted.observations }}
            {{ fitted.observations === 1 ? 'answer' : 'answers' }}. Axes marked
            <em>not asked</em> were never contrasted, so the number is the
            population's rather than yours — which is a different thing from
            having no preference, and shown differently.
          </p>

          <div class="profile-actions">
            <a mat-flat-button routerLink="/tonight">
              <mat-icon>bolt</mat-icon>
              See tonight's pick
            </a>
            <button mat-stroked-button [disabled]="busy()" (click)="restart()">
              Start again
            </button>
          </div>
        </section>
      }
    </section>
  `,
  styles: `
    .page {
      max-width: 44rem;
    }

    .sub {
      opacity: 0.8;
      max-width: 34rem;
    }

    .progress-line {
      margin: 1.5rem 0 0.4rem;
      font-size: 0.85rem;
      opacity: 0.8;
    }

    .axis {
      opacity: 0.7;
    }

    .prompt {
      margin: 1.5rem 0 0.75rem;
      font-size: 1.1rem;
    }

    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1rem;
    }

    .option {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      padding: 0.75rem;
      border: 1px solid var(--plotted-border, rgba(255, 255, 255, 0.15));
      border-radius: 0.75rem;
      background: var(--plotted-surface-raised);
      color: inherit;
      cursor: pointer;
      text-align: left;
      font: inherit;
    }

    .option:hover:not(:disabled),
    .option:focus-visible {
      border-color: var(--plotted-accent, #ffb300);
    }

    .option:disabled {
      opacity: 0.6;
      cursor: default;
    }

    .option img,
    .poster-fallback {
      width: 100%;
      aspect-ratio: 2 / 3;
      object-fit: cover;
      border-radius: 0.5rem;
    }

    .poster-fallback {
      display: grid;
      place-items: center;
      background: rgba(255, 255, 255, 0.06);
    }

    .name {
      font-weight: 600;
    }

    .meta {
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
    }

    .skip {
      margin-top: 1rem;
      width: 100%;
    }

    .skip-note {
      margin: 0.4rem 0 0;
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
      text-align: center;
    }

    .ended {
      margin-top: 1.5rem;
    }

    .degraded {
      opacity: 0.8;
      max-width: 34rem;
    }

    .profile {
      margin-top: 2.5rem;
    }

    .axes {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 0.5rem;
    }

    .axes li {
      display: grid;
      grid-template-columns: 8rem 1fr auto;
      gap: 0.75rem;
      align-items: baseline;
      padding: 0.5rem 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.07);
    }

    .axes li.unstated {
      opacity: 0.6;
    }

    .label {
      font-weight: 600;
    }

    .verdict {
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
      white-space: nowrap;
    }

    .footnote {
      font-size: 0.8rem;
      color: var(--plotted-text-faint);
      max-width: 34rem;
    }

    .profile-actions {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
      margin-top: 1rem;
    }

    .form-error {
      color: var(--mat-sys-error, #b3261e);
    }

    @media (max-width: 30rem) {
      .axes li {
        grid-template-columns: 1fr;
        gap: 0.15rem;
      }
    }
  `,
})
export class PilotPage implements OnInit {
  private readonly pilot = inject(PilotService);

  protected readonly state = signal<PilotState | null>(null);
  protected readonly profile = signal<PreferenceProfile | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.pilot.next().subscribe({
      next: (state) => {
        this.state.set(state);
        this.loading.set(false);
        if (state.answered > 0) this.loadProfile();
      },
      error: (failure: unknown) => {
        this.loading.set(false);
        this.error.set(messageFrom(failure, 'The questionnaire could not be loaded.'));
      },
    });
  }

  protected choose(titleId: string): void {
    this.send(titleId);
  }

  protected skip(): void {
    this.send(undefined);
  }

  protected restart(): void {
    this.busy.set(true);
    this.pilot.reset().subscribe({
      next: () => {
        this.profile.set(null);
        this.busy.set(false);
        this.ngOnInit();
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.busy.set(false);
      },
    });
  }

  /**
   * Posts an answer, or a skip when `chosenTitleId` is undefined.
   *
   * The next state comes from the response rather than from a second request, so
   * what the progress bar shows is what the server actually recorded.
   */
  private send(chosenTitleId: string | undefined): void {
    const question = this.state()?.question;
    if (!question) return;

    this.busy.set(true);
    this.error.set(null);
    this.pilot
      .answer({
        leftTitleId: question.left.titleId,
        rightTitleId: question.right.titleId,
        ...(chosenTitleId ? { chosenTitleId } : {}),
      })
      .subscribe({
        next: (state) => {
          this.state.set(state);
          this.busy.set(false);
          // Only once there is something to fit. Asking earlier would get a 204
          // and set nothing, which is right but a wasted round trip per answer.
          if (state.complete && state.answered > 0) this.loadProfile();
        },
        error: (failure: unknown) => {
          this.error.set(messageFrom(failure));
          this.busy.set(false);
        },
      });
  }

  private loadProfile(): void {
    this.pilot.profile().subscribe({
      // 204 arrives as null. Nothing answered yet is not an error.
      next: (fitted) => this.profile.set(fitted),
      error: () => this.profile.set(null),
    });
  }

  protected verdictLabel(verdict: string): string {
    switch (verdict) {
      case 'LIKES':
      case 'DISLIKES':
        return 'a finding';
      case 'NO_PREFERENCE':
        return 'asked, balanced';
      default:
        return 'not asked';
    }
  }
}

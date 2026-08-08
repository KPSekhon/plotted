import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import { AxisOpinion, PilotState, PreferenceProfile } from '../../core/pilot/pilot.models';
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

          <p class="prompt">Which way?</p>

          <!-- A fork with three directions, not two options and an escape
               hatch. The two branches are the preference-learning routes; the
               neutral continuation below is "haven't seen either", which is a
               real third direction because choosing it communicates no
               preference rather than a weak one. It is drawn straight down and
               grey precisely so it reads as neutral rather than lesser. -->
          <div class="fork">
            <svg class="fork-lines" viewBox="0 0 120 200" fill="none" aria-hidden="true"
                 preserveAspectRatio="none">
              <circle class="origin" cx="8" cy="100" r="4" />
              <path class="branch" d="M12 100 C 60 100, 60 40, 116 40" />
              <path class="branch" d="M12 100 C 60 100, 60 160, 116 160" />
              <path class="branch neutral" d="M12 100 C 40 100, 40 196, 116 196" />
            </svg>

            <div class="choices">
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
                  <span class="meta coordinates">
                    {{ option.mediaType === 'movie' ? 'Film' : 'Series' }}
                    @if (option.releaseYear) {
                      &middot; {{ option.releaseYear }}
                    }
                  </span>
                </button>
              }

              <button class="option neither" type="button" [disabled]="busy()" (click)="skip()">
                <span class="waypoint" aria-hidden="true"></span>
                <span class="name">Haven't seen either</span>
                <span class="meta coordinates">Records nothing about your taste</span>
              </button>
            </div>
          </div>
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

          <!-- Literally plotted: each axis is a line between its two poles and
               the point sits where the fit put you. The unstated ones show a
               question mark at the centre rather than a point, because a dot
               in the middle would claim "measured, and balanced" — which is a
               finding, and a different one from never having been asked. -->
          <ul class="axes">
            @for (axis of fitted.axes; track axis.axis) {
              <li [class.unstated]="!axis.stated">
                <span class="poles coordinates">
                  <span>{{ axis.negative }}</span>
                  <span>{{ axis.positive }}</span>
                </span>

                <span class="axis-track" aria-hidden="true">
                  @if (axis.stated) {
                    <span class="axis-point" [style.left.%]="position(axis)"></span>
                  } @else {
                    <span class="axis-unknown">?</span>
                  }
                </span>

                <span class="sentence">{{ axis.sentence }}</span>
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

    .fork {
      display: grid;
      grid-template-columns: 7rem minmax(0, 1fr);
      align-items: stretch;
      gap: 0;
    }

    .fork-lines {
      width: 7rem;
      height: 100%;
    }

    .fork-lines .origin { fill: var(--plotted-accent); }

    .fork-lines .branch {
      stroke: var(--plotted-border-strong);
      stroke-width: 1.5;
      vector-effect: non-scaling-stroke;
    }

    .choices {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1rem;
    }

    /* The neutral direction spans both columns: it is one road, not a third
       poster, and it must not look like a smaller version of a choice. */
    .neither {
      grid-column: 1 / -1;
      flex-direction: row;
      align-items: center;
      gap: 0.6rem;
    }

    /* The fork drawing carries no information a screen reader needs and stops
       being legible once the branches are shorter than the posters. */
    @media (max-width: 40rem) {
      .fork { grid-template-columns: minmax(0, 1fr); }
      .fork-lines { display: none; }
    }

    .option {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      padding: 0.75rem;
      border: 1px solid var(--plotted-border);
      border-radius: 0.75rem;
      background: var(--plotted-surface-raised);
      color: inherit;
      cursor: pointer;
      text-align: left;
      font: inherit;
      transition: border-color 0.15s ease;
    }

    /* Hover goes orange on the two branches because hovering here really is
       "this is the way I would go". The neutral road stays grey for the same
       reason: choosing it expresses no preference. */
    .option:hover:not(:disabled),
    .option:focus-visible {
      border-color: var(--plotted-accent);
    }

    .neither:hover:not(:disabled),
    .neither:focus-visible {
      border-color: var(--plotted-text-faint);
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
      background: var(--plotted-surface-raised);
    }

    .name {
      font-weight: 600;
    }

    .meta {
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
    }

    .poles {
      display: flex;
      justify-content: space-between;
      font-size: 0.62rem;
    }

    .axis-track {
      position: relative;
      display: block;
      height: 1px;
      background: var(--plotted-border-strong);
      margin: 0.5rem 0;
    }

    .axis-point {
      position: absolute;
      top: 50%;
      width: 0.5rem;
      height: 0.5rem;
      margin: -0.25rem 0 0 -0.25rem;
      border-radius: 50%;
      background: var(--plotted-accent);
    }

    .axis-unknown {
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      background: var(--plotted-bg);
      padding: 0 0.35rem;
      font-family: var(--plotted-mono);
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
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
      gap: 1.1rem;
    }

    .axes li {
      display: block;
      padding: 0 0 0.75rem;
      border-bottom: 1px solid var(--plotted-border);
    }

    /* Dimmed rather than hidden. An axis nobody asked about is part of the
       answer, and the footnote below explains why it looks different. */
    .axes li.unstated {
      opacity: 0.55;
    }

    .sentence {
      font-size: 0.85rem;
      color: var(--plotted-text-muted);
    }

    .label {
      font-weight: 600;
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
      color: var(--plotted-critical);
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

  /**
   * Where the point sits on its axis, as a percentage from the negative pole.
   *
   * `tanh` rather than a linear scale against some assumed maximum: the fitted
   * weight is unbounded, so any linear mapping needs a cap, and a capped point
   * silently stops moving once someone's preference is strong enough. This
   * squashes smoothly — 0 lands dead centre, strong preferences approach the
   * ends without ever reaching or exceeding them.
   *
   * Only ever called for a *stated* axis. An unstated one renders a question
   * mark instead, because placing a dot at the centre would claim we measured
   * indifference when we simply never asked.
   */
  protected position(axis: AxisOpinion): number {
    return 50 + 50 * Math.tanh(axis.weight);
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

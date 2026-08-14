import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';

import { Availability, Title } from '../../core/catalogue/catalogue.models';
import { CatalogueService } from '../../core/catalogue/catalogue.service';
import { messageFrom } from '../../core/error/problem-detail';
import { UserSettingsService } from '../../core/user/user-settings.service';
import { SeriesProgress } from '../../core/watchlist/watchlist.models';
import { WatchlistService } from '../../core/watchlist/watchlist.service';
import { RuntimeRouteComponent } from '../../shared/map/runtime-route.component';
import { AvailabilityPanelComponent } from './availability-panel.component';

/**
 * One title, and where to watch it.
 *
 * The two loads are independent on purpose: availability failing must not blank
 * the title, because "here is the film, availability is temporarily unknown" is
 * the documented degraded state — an error page is not.
 */
@Component({
  selector: 'plotted-title-detail',
  standalone: true,
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    AvailabilityPanelComponent,
    RuntimeRouteComponent,
  ],
  template: `
    <a mat-button routerLink="/search" class="back">
      <mat-icon>arrow_back</mat-icon>
      Back to search
    </a>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" aria-label="Loading title" />
    }

    @if (error(); as message) {
      <p class="form-error" role="alert">{{ message }}</p>
    }

    @if (title(); as loaded) {
      <article class="detail">
        <div class="poster-column">
          @if (loaded.posterUrl; as poster) {
            <img [src]="poster" [alt]="'Poster for ' + loaded.name" />
          } @else {
            <div class="poster-fallback" aria-hidden="true">
              <mat-icon>movie</mat-icon>
            </div>
          }
        </div>

        <div class="info-column">
          <h1>{{ loaded.name }}</h1>
          @if (loaded.originalName; as original) {
            <p class="original-name">{{ original }}</p>
          }

          <!-- Map annotations rather than a sentence: these are readings, and
               reading them as readings is the point. -->
          <dl class="facts coordinates">
            <div>
              <dt>Format</dt>
              <dd>{{ loaded.mediaType === 'movie' ? 'Film' : 'Series' }}</dd>
            </div>
            @if (loaded.releaseDate; as date) {
              <div>
                <dt>Year</dt>
                <dd class="readout">{{ date | date: 'yyyy' }}</dd>
              </div>
            }
            @if (loaded.mediaType === 'series' && loaded.episodeCount; as episodes) {
              <div>
                <dt>Episodes</dt>
                <dd class="readout">{{ episodes }}</dd>
              </div>
            }
            @if (loaded.communityRating; as rating) {
              <div>
                <dt>Rated</dt>
                <dd class="readout">{{ rating }}/10</dd>
              </div>
            }
          </dl>

          <plotted-runtime-route
            [watchMinutes]="loaded.watchMinutes"
            [episodeCount]="loaded.episodeCount"
            [isSeries]="loaded.mediaType === 'series'"
            [availableMinutes]="usualEvening()"
          />

          <!-- Where you are, and the way to correct it. The Tonight card
               advances by one; this is the repair when that was wrong, or when
               you watched three episodes somewhere else. Deliberately not a
               per-episode checklist: Plotted needs one position, and a grid of
               tick boxes is a progress manager rather than a decision. -->
          @if (loaded.mediaType === 'series') {
            <section class="progress" aria-label="Your place in this series">
              @if (progress(); as place) {
                <p class="progress__state">
                  @if (place.caughtUp && place.lastCompleted) {
                    <span class="coordinates">Completed</span>
                    <span class="muted">Nothing aired is left.</span>
                  } @else {
                    <!-- Nested rather than aliased on the @else if: the block
                         syntax only binds an "as" alias on a leading @if, and
                         this file's own header records the same trap. -->
                    @if (place.next; as next) {
                      <span class="coordinates">{{ place.lastCompleted ? 'You are here' : 'Not started' }}</span>
                      <span class="progress__code readout">S{{ next.seasonNumber }} E{{ next.episodeNumber }}</span>
                      @if (next.name) {
                        <span class="muted">{{ next.name }}</span>
                      }
                      <span class="muted">&middot; {{ place.remaining.episodes }} left</span>
                    }
                  }
                </p>

                <form class="progress__form" (ngSubmit)="saveProgress()">
                  <label>
                    <span class="coordinates">Season</span>
                    <input type="number" min="1" [(ngModel)]="progressSeason" name="progressSeason" />
                  </label>
                  <label>
                    <span class="coordinates">Episode</span>
                    <input type="number" min="1" [(ngModel)]="progressEpisode" name="progressEpisode" />
                  </label>
                  <button type="submit" [disabled]="savingProgress()">Set progress</button>
                  @if (place.lastCompleted) {
                    <button type="button" class="link-button" [disabled]="savingProgress()" (click)="resetProgress()">
                      Not started
                    </button>
                  }
                </form>

                @if (progressError(); as failure) {
                  <p class="error" role="alert">{{ failure }}</p>
                }
              }
            </section>
          }

          @if (!loaded.watchMinutes) {
            <p class="incomplete-note">
              <mat-icon inline>info</mat-icon>
              Until the next metadata refresh fills that in, this cannot appear in
              time-constrained recommendations.
            </p>
          }

          @if (loaded.overview; as overview) {
            <p class="overview">{{ overview }}</p>
          }

          <div class="list-actions">
            @if (onList()) {
              <!-- Confirmed rather than assumed: the button reflects what the
                   server came back with, so a failed add cannot leave the
                   interface claiming something is saved when it is not. -->
              <a mat-stroked-button routerLink="/watchlist">
                <mat-icon>check</mat-icon>
                On your list
              </a>
            } @else {
              <button mat-flat-button (click)="addToList(loaded.id)" [disabled]="adding()">
                <mat-icon>add</mat-icon>
                Add to your list
              </button>
            }
            @if (blocked()) {
              <button mat-stroked-button (click)="unblock(loaded.id)" [disabled]="blocking()">
                <mat-icon>block</mat-icon>
                Blocked &middot; undo
              </button>
            } @else {
              <button mat-stroked-button (click)="block(loaded.id)" [disabled]="blocking()">
                <mat-icon>block</mat-icon>
                Not interested
              </button>
            }

            @if (listError(); as message) {
              <span class="list-error" role="alert">{{ message }}</span>
            }
          </div>

          @if (blocked()) {
            <!-- Says what blocking actually does. It is not a delete: the title
                 stays in the catalogue and on the list if it was there, and the
                 undo above restores it whole. -->
            <p class="blocked-note">
              <mat-icon inline>info</mat-icon>
              Tonight Mode and the subscription planner will skip this. It stays searchable, and
              anything you wrote about it on your list is untouched.
            </p>
          }

          @if (availability(); as loadedAvailability) {
            <plotted-availability-panel [availability]="loadedAvailability" />
          } @else if (availabilityError()) {
            <section aria-label="Where to watch">
              <h2>Where to watch</h2>
              <p class="degraded">
                Availability could not be loaded right now. The title is unaffected — try again
                in a moment.
              </p>
            </section>
          } @else if (!loading()) {
            <mat-progress-bar mode="indeterminate" aria-label="Loading availability" />
          }
        </div>
      </article>
    }
  `,
  styles: `
    .back {
      margin-bottom: 1rem;
    }

    .detail {
      display: grid;
      grid-template-columns: minmax(10rem, 14rem) 1fr;
      gap: 2rem;
      align-items: start;
    }

    @media (max-width: 40rem) {
      .detail {
        grid-template-columns: 1fr;
      }

      .poster-column {
        max-width: 12rem;
      }
    }

    .poster-column img,
    .poster-fallback {
      width: 100%;
      aspect-ratio: 2 / 3;
      object-fit: cover;
      border-radius: 0.75rem;
    }

    .poster-fallback {
      display: grid;
      place-items: center;
      background: var(--plotted-surface-raised);

      mat-icon {
        font-size: 3rem;
        width: 3rem;
        height: 3rem;
        opacity: 0.4;
      }
    }

    h1 {
      margin-bottom: 0.15rem;
    }

    .original-name {
      opacity: 0.7;
      margin: 0 0 0.5rem;
    }

    .facts {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem 1.75rem;
      margin: 0.75rem 0 0;

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
        font-size: 0.82rem;
        color: var(--plotted-text-muted);
        letter-spacing: 0;
        text-transform: none;
      }
    }

    .progress {
      margin: 1rem 0 0;
      padding: 0.85rem 1rem;
      border: 1px solid var(--plotted-border);
      border-radius: var(--plotted-radius-sm);
      background: var(--plotted-surface);
    }

    .progress__state {
      display: flex;
      align-items: baseline;
      flex-wrap: wrap;
      gap: 0.45rem;
      margin: 0 0 0.7rem;
      font-size: 0.9rem;
    }

    .progress__code {
      color: var(--plotted-accent);
      font-weight: 600;
    }

    .progress__form {
      display: flex;
      align-items: flex-end;
      flex-wrap: wrap;
      gap: 0.6rem;

      label {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        font-size: 0.7rem;
      }

      input {
        width: 4.5rem;
        padding: 0.3rem 0.4rem;
        border: 1px solid var(--plotted-border);
        border-radius: 2px;
        background: var(--plotted-bg);
        color: var(--plotted-text);
        font: inherit;
      }

      button[type='submit'] {
        padding: 0.35rem 0.75rem;
        border: 1px solid var(--plotted-border-strong);
        border-radius: 999px;
        background: none;
        color: var(--plotted-text);
        font: inherit;
        font-size: 0.8125rem;
        cursor: pointer;
      }
    }

    .link-button {
      padding: 0;
      border: 0;
      background: none;
      color: var(--plotted-accent);
      font: inherit;
      font-size: 0.8125rem;
      text-decoration: underline;
      cursor: pointer;
    }

    .incomplete-note {
      display: flex;
      gap: 0.4rem;
      align-items: baseline;
      font-size: 0.85rem;
      opacity: 0.75;
      max-width: 36rem;
    }

    .overview {
      max-width: 44rem;
      line-height: 1.6;
      margin-bottom: 2rem;
    }

    .degraded {
      opacity: 0.75;
    }

    .form-error {
      color: var(--plotted-critical);
    }

    .list-actions {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      flex-wrap: wrap;
      margin-bottom: 2rem;
    }

    .list-error {
      font-size: 0.85rem;
      color: var(--plotted-critical);
    }

    .blocked-note {
      display: flex;
      gap: 0.4rem;
      align-items: baseline;
      font-size: 0.85rem;
      opacity: 0.75;
      max-width: 36rem;
      margin: -1.25rem 0 2rem;
    }
  `,
})
export class TitleDetailPage implements OnInit {
  /** Bound from the route by withComponentInputBinding. */
  readonly titleId = input.required<string>();

  private readonly catalogue = inject(CatalogueService);
  private readonly watchlists = inject(WatchlistService);
  private readonly settings = inject(UserSettingsService);

  protected readonly title = signal<Title | null>(null);
  protected readonly availability = signal<Availability | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /**
   * The user's stated usual evening, used to draw a boundary across the runtime.
   *
   * Null until settings load, and left null if they fail — the runtime bar then
   * simply has no window on it. A default invented here would put a limit on
   * screen that the user never set and would then read as their own.
   */
  protected readonly usualEvening = signal<number | null>(null);

  protected readonly progress = signal<SeriesProgress | null>(null);
  protected readonly savingProgress = signal(false);
  protected readonly progressError = signal<string | null>(null);
  protected progressSeason: number | null = null;
  protected progressEpisode: number | null = null;

  protected readonly onList = signal(false);
  protected readonly adding = signal(false);
  protected readonly listError = signal<string | null>(null);

  protected readonly blocked = signal(false);
  protected readonly blocking = signal(false);

  /**
   * Adds this title to the default watchlist.
   *
   * The API is idempotent, so a double click is harmless rather than a second
   * row -- adding something twice is a slip, not an error worth reporting.
   */
  protected addToList(titleId: string): void {
    this.adding.set(true);
    this.listError.set(null);
    this.watchlists.add({ titleId }).subscribe({
      next: () => {
        this.onList.set(true);
        this.adding.set(false);
      },
      error: (failure: unknown) => {
        this.listError.set(messageFrom(failure));
        this.adding.set(false);
      },
    });
  }
  /**
   * Blocks this title, so neither recommender offers it again.
   *
   * The state is set from the response rather than optimistically, for the same
   * reason the add button is: a failed request must not leave the interface
   * claiming a preference the server never recorded.
   */
  protected block(titleId: string): void {
    this.blocking.set(true);
    this.listError.set(null);
    this.watchlists.block({ titleId }).subscribe({
      next: () => {
        this.blocked.set(true);
        this.blocking.set(false);
      },
      error: (failure: unknown) => {
        this.listError.set(messageFrom(failure));
        this.blocking.set(false);
      },
    });
  }

  protected unblock(titleId: string): void {
    this.blocking.set(true);
    this.listError.set(null);
    this.watchlists.unblock(titleId).subscribe({
      next: () => {
        this.blocked.set(false);
        this.blocking.set(false);
      },
      error: (failure: unknown) => {
        this.listError.set(messageFrom(failure));
        this.blocking.set(false);
      },
    });
  }

  protected readonly availabilityError = signal(false);

  ngOnInit(): void {
    this.catalogue.get(this.titleId()).subscribe({
      next: (title) => {
        this.title.set(title);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.loading.set(false);
        this.error.set(messageFrom(failure, 'This title could not be loaded.'));
      },
    });

    this.catalogue.availability(this.titleId()).subscribe({
      next: (availability) => this.availability.set(availability),
      // Availability failing must not blank the title.
      error: () => this.availabilityError.set(true),
    });

    // Series progress, loaded independently and failing silently like the
    // others: a signed-out visitor gets a 401, and a film has no progress to
    // show. Neither is worth an error banner on a page about a title.
    this.watchlists.progress(this.titleId()).subscribe({
      next: (place) => {
        this.progress.set(place);
        this.progressSeason = place.lastCompleted?.seasonNumber ?? place.next?.seasonNumber ?? 1;
        this.progressEpisode = place.lastCompleted?.episodeNumber ?? null;
      },
      error: () => this.progress.set(null),
    });

    this.watchlists.blocked().subscribe({
      next: (blocked) => this.blocked.set(blocked.blocked.some((it) => it.titleId === this.titleId())),
      // A signed-out visitor gets a 401 here. Leaving the control in its
      // unblocked state is right for them, and surfacing an error for a
      // preference they have not expressed would be noise.
      error: () => this.blocked.set(false),
    });

    // A fourth independent load, and it fails silently for the same reason the
    // others do: the runtime bar without a window is still useful, and an error
    // banner about settings on a title page would be about the wrong thing.
    this.settings.get().subscribe({
      next: (settings) => this.usualEvening.set(settings.defaultAvailableMinutes),
      error: () => this.usualEvening.set(null),
    });
  }

  protected formatMinutes(minutes: number): string {
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }

  /**
   * Records where they actually are.
   *
   * The correction path, as opposed to Tonight's "Watched it", which advances by
   * one. Somebody who watched four episodes on a plane needs to say so once
   * rather than press a button four times, and somebody who mis-tapped needs to
   * put the marker back exactly.
   */
  protected saveProgress(): void {
    const season = this.progressSeason;
    const episode = this.progressEpisode;
    if (season === null || episode === null) return;

    this.savingProgress.set(true);
    this.progressError.set(null);
    this.watchlists.recordProgress(this.titleId(), season, episode).subscribe({
      next: (place) => {
        this.progress.set(place);
        this.savingProgress.set(false);
      },
      error: (failure: unknown) => {
        // Surfaced rather than swallowed, unlike the initial load: the user just
        // asked for something specific, and a season that does not exist is a
        // 400 naming it.
        this.progressError.set(messageFrom(failure, 'That episode could not be recorded.'));
        this.savingProgress.set(false);
      },
    });
  }

  /** Back to not started, which is a state rather than an absence of one. */
  protected resetProgress(): void {
    this.savingProgress.set(true);
    this.progressError.set(null);
    this.watchlists.clearProgress(this.titleId()).subscribe({
      next: (place) => {
        this.progress.set(place);
        this.progressEpisode = null;
        this.progressSeason = place.next?.seasonNumber ?? 1;
        this.savingProgress.set(false);
      },
      error: (failure: unknown) => {
        this.progressError.set(messageFrom(failure, 'Progress could not be cleared.'));
        this.savingProgress.set(false);
      },
    });
  }
}

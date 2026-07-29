import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';

import { Availability, Title } from '../../core/catalogue/catalogue.models';
import { CatalogueService } from '../../core/catalogue/catalogue.service';
import { messageFrom } from '../../core/error/problem-detail';
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
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    AvailabilityPanelComponent,
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

          <p class="facts">
            <span>{{ loaded.mediaType === 'movie' ? 'Film' : 'Series' }}</span>
            @if (loaded.releaseDate; as date) {
              <span>&middot; {{ date | date: 'yyyy' }}</span>
            }
            @if (loaded.watchMinutes; as minutes) {
              <span>&middot; {{ formatMinutes(minutes) }}</span>
              @if (loaded.mediaType === 'series' && loaded.episodeCount; as episodes) {
                <span>across {{ episodes }} episodes</span>
              }
            }
            @if (loaded.communityRating; as rating) {
              <span>&middot; {{ rating }}/10</span>
            }
          </p>

          @if (!loaded.watchMinutes) {
            <p class="incomplete-note">
              <mat-icon inline>info</mat-icon>
              Plotted does not know how long this is yet, so it cannot appear in
              time-constrained recommendations until the next metadata refresh fills that in.
            </p>
          }

          @if (loaded.overview; as overview) {
            <p class="overview">{{ overview }}</p>
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
      gap: 0.4rem;
      flex-wrap: wrap;
      opacity: 0.8;
      margin: 0 0 1rem;
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
      color: var(--mat-sys-error, #b3261e);
    }
  `,
})
export class TitleDetailPage implements OnInit {
  /** Bound from the route by withComponentInputBinding. */
  readonly titleId = input.required<string>();

  private readonly catalogue = inject(CatalogueService);

  protected readonly title = signal<Title | null>(null);
  protected readonly availability = signal<Availability | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
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
  }

  protected formatMinutes(minutes: number): string {
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }
}

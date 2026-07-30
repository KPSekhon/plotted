import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

import { Title } from '../../core/catalogue/catalogue.models';

/**
 * One title in a results grid. Runtime is always rendered when known and
 * flagged when not, because "how long is this?" is the first question a
 * time-boxed viewer asks — it is the product's whole premise.
 */
@Component({
  selector: 'plotted-title-card',
  standalone: true,
  imports: [DatePipe, RouterLink, MatCardModule, MatIconModule],
  template: `
    <a class="card-link" [routerLink]="['/titles', title().id]">
      <mat-card class="title-card">
        @if (title().posterUrl; as poster) {
          <img
            mat-card-image
            [src]="poster"
            [alt]="'Poster for ' + title().name"
            loading="lazy"
          />
        } @else {
          <div class="poster-fallback" aria-hidden="true">
            <mat-icon>movie</mat-icon>
          </div>
        }
        <mat-card-content>
          <h3 class="name">{{ title().name }}</h3>
          <p class="meta">
            <span>{{ title().mediaType === 'movie' ? 'Film' : 'Series' }}</span>
            @if (title().releaseDate; as date) {
              <span>&middot; {{ date | date: 'yyyy' }}</span>
            }
            @if (title().watchMinutes; as minutes) {
              <span>&middot; {{ formatMinutes(minutes) }}</span>
            } @else {
              <span class="unknown">&middot; length unknown</span>
            }
          </p>
        </mat-card-content>
      </mat-card>
    </a>
  `,
  styles: `
    .card-link {
      text-decoration: none;
      color: inherit;
      display: block;
      height: 100%;
    }

    .title-card {
      height: 100%;
      overflow: hidden;
      transition: transform 0.14s ease, border-color 0.14s ease;
    }

    .card-link:hover .title-card,
    .card-link:focus-visible .title-card {
      transform: translateY(-3px);
      border-color: var(--plotted-border-strong);
    }

    img[mat-card-image],
    .poster-fallback {
      aspect-ratio: 2 / 3;
      object-fit: cover;
      width: 100%;
      margin: 0;
      display: block;
    }

    .poster-fallback {
      display: grid;
      place-items: center;
      background: var(--plotted-surface-raised);

      mat-icon {
        font-size: 2.5rem;
        width: 2.5rem;
        height: 2.5rem;
        color: var(--plotted-text-faint);
      }
    }

    mat-card-content {
      padding: 0.7rem 0.85rem 0.9rem !important;
    }

    .name {
      font-size: 0.92rem;
      font-weight: 600;
      letter-spacing: -0.01em;
      margin: 0 0 0.2rem;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    .meta {
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
      margin: 0;
      display: flex;
      gap: 0.25rem;
      flex-wrap: wrap;
    }

    /* Length is the first thing a time-boxed viewer needs, so its absence is
       stated rather than left as a blank. */
    .unknown {
      font-style: italic;
    }
  `,
})
export class TitleCardComponent {
  readonly title = input.required<Title>();

  protected formatMinutes(minutes: number): string {
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }
}

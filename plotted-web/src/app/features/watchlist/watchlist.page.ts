import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import {
  PRIORITY_LABELS,
  STATUS_LABELS,
  WatchStatus,
  WatchlistItem,
} from '../../core/watchlist/watchlist.models';
import { WatchlistService } from '../../core/watchlist/watchlist.service';

/**
 * The watchlist.
 *
 * Priority is editable in place rather than behind a dialog, because it is the
 * field the coverage dashboard and every later ranking actually read, and a
 * setting nobody adjusts is a setting that stays at its default and makes the
 * weighting meaningless.
 */
@Component({
  selector: 'plotted-watchlist',
  standalone: true,
  imports: [
    DatePipe,
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  template: `
    <section class="page">
      <header class="head">
        <div>
          <h1>Your list</h1>
          <p class="sub">
            1 is the highest priority. It decides what Plotted recommends first and how coverage
            is weighted.
          </p>
        </div>
        <a mat-stroked-button routerLink="/search">
          <mat-icon>search</mat-icon>
          Find something
        </a>
      </header>

      @if (loading()) {
        <div class="centre"><mat-spinner diameter="36" /></div>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else if (items().length === 0) {
        <div class="empty">
          <mat-icon>bookmark_border</mat-icon>
          <h2>Nothing on your list yet</h2>
          <p>
            Add a few things you actually intend to watch. Coverage and recommendations both read
            this list, so they stay empty until it does.
          </p>
          <a mat-flat-button routerLink="/search">Search the catalogue</a>
        </div>
      } @else {
        <div class="groups">
          @if (outstanding().length > 0) {
            <h2 class="group-head">Still to watch ({{ outstanding().length }})</h2>
            @for (item of outstanding(); track item.id) {
              <ng-container [ngTemplateOutlet]="row" [ngTemplateOutletContext]="{ $implicit: item }" />
            }
          }
          @if (done().length > 0) {
            <h2 class="group-head muted">Finished and set aside ({{ done().length }})</h2>
            @for (item of done(); track item.id) {
              <ng-container [ngTemplateOutlet]="row" [ngTemplateOutletContext]="{ $implicit: item }" />
            }
          }
        </div>
      }

      <ng-template #row let-item>
        <article class="item" [class.settled]="!isOutstanding(item)">
          @if (item.title?.posterUrl) {
            <img [src]="item.title.posterUrl" [alt]="'Poster for ' + item.title.name" loading="lazy" />
          } @else {
            <div class="poster-fallback" aria-hidden="true"><mat-icon>movie</mat-icon></div>
          }

          <div class="detail">
            @if (item.title) {
              <a class="name" [routerLink]="['/titles', item.titleId]">{{ item.title.name }}</a>
              <p class="meta">
                <span>{{ item.title.mediaType === 'movie' ? 'Film' : 'Series' }}</span>
                @if (item.title.releaseYear) {
                  <span>&middot; {{ item.title.releaseYear }}</span>
                }
                @if (item.title.watchMinutes) {
                  <span>&middot; {{ formatMinutes(item.title.watchMinutes) }}</span>
                } @else {
                  <span class="unknown">&middot; length unknown</span>
                }
                <!-- Only when it is known. A finished item completed before the
                     API recorded completion times has none, and printing the
                     date it was added instead would be inventing the answer. -->
                @if (item.completedAt) {
                  <span>&middot; finished {{ item.completedAt | date: 'mediumDate' }}</span>
                }
              </p>
            } @else {
              <!-- The title is gone from the catalogue but the intent is not, so
                   the row stays and says so rather than vanishing. -->
              <span class="name missing">Title no longer in the catalogue</span>
              <p class="meta">Added {{ item.addedAt | date: 'mediumDate' }}</p>
            }
          </div>

          <mat-select
            class="control"
            [ngModel]="item.priority"
            (ngModelChange)="setPriority(item, $event)"
            [disabled]="busy() === item.id"
            aria-label="Priority"
          >
            @for (option of priorities; track option) {
              <mat-option [value]="option">{{ option }} &middot; {{ priorityLabel(option) }}</mat-option>
            }
          </mat-select>

          <mat-select
            class="control"
            [ngModel]="item.status"
            (ngModelChange)="setStatus(item, $event)"
            [disabled]="busy() === item.id"
            aria-label="Status"
          >
            @for (option of statuses; track option) {
              <mat-option [value]="option">{{ statusLabel(option) }}</mat-option>
            }
          </mat-select>

          <button
            mat-icon-button
            (click)="remove(item)"
            [disabled]="busy() === item.id"
            matTooltip="Remove from list"
            [attr.aria-label]="'Remove ' + (item.title?.name ?? 'this title') + ' from your list'"
          >
            <mat-icon>close</mat-icon>
          </button>
        </article>
      </ng-template>
    </section>
  `,
  styles: `
    .page {
      max-width: 60rem;
      margin: 0 auto;
      padding: 1.5rem 1rem 3rem;
    }

    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      flex-wrap: wrap;
      margin-bottom: 1.5rem;
    }

    h1 {
      margin: 0 0 0.25rem;
      font-size: 1.6rem;
      letter-spacing: -0.02em;
    }

    .sub {
      margin: 0;
      color: var(--plotted-text-faint);
      font-size: 0.85rem;
      max-width: 34rem;
    }

    .group-head {
      font-size: 0.78rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--plotted-text-faint);
      margin: 1.75rem 0 0.6rem;
      font-weight: 600;
    }

    .group-head.muted {
      opacity: 0.7;
    }

    .item {
      display: grid;
      grid-template-columns: 3rem 1fr 11rem 9rem 2.5rem;
      gap: 0.9rem;
      align-items: center;
      padding: 0.6rem;
      border: 1px solid var(--plotted-border);
      border-radius: 10px;
      background: var(--plotted-surface);
      margin-bottom: 0.5rem;
    }

    /* Finished items stay visible but recede: they are history, and they do not
       count towards coverage. */
    .item.settled {
      opacity: 0.62;
    }

    img,
    .poster-fallback {
      width: 3rem;
      aspect-ratio: 2 / 3;
      object-fit: cover;
      border-radius: 4px;
      display: block;
    }

    .poster-fallback {
      display: grid;
      place-items: center;
      background: var(--plotted-surface-raised);
      color: var(--plotted-text-faint);
    }

    .detail {
      min-width: 0;
    }

    .name {
      font-weight: 600;
      font-size: 0.95rem;
      color: inherit;
      text-decoration: none;
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .name:hover {
      text-decoration: underline;
    }

    .name.missing {
      color: var(--plotted-text-faint);
      font-style: italic;
      font-weight: 500;
    }

    .meta {
      margin: 0.15rem 0 0;
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
      display: flex;
      gap: 0.25rem;
      flex-wrap: wrap;
    }

    .unknown {
      font-style: italic;
    }

    .control {
      font-size: 0.85rem;
    }

    .centre {
      display: grid;
      place-items: center;
      padding: 3rem;
    }

    .empty {
      text-align: center;
      padding: 3.5rem 1rem;
      border: 1px dashed var(--plotted-border);
      border-radius: 12px;

      mat-icon {
        font-size: 2.5rem;
        width: 2.5rem;
        height: 2.5rem;
        color: var(--plotted-text-faint);
      }

      h2 {
        margin: 0.75rem 0 0.4rem;
        font-size: 1.1rem;
      }

      p {
        color: var(--plotted-text-faint);
        max-width: 26rem;
        margin: 0 auto 1.25rem;
        font-size: 0.88rem;
      }
    }

    .error {
      color: var(--plotted-danger);
      font-size: 0.88rem;
    }

    @media (max-width: 720px) {
      .item {
        grid-template-columns: 3rem 1fr 2.5rem;
        grid-template-areas:
          'poster detail remove'
          'priority priority priority'
          'status status status';
      }
    }
  `,
})
export class WatchlistPage {
  private readonly watchlists = inject(WatchlistService);

  protected readonly items = signal<readonly WatchlistItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);

  protected readonly priorities = [1, 2, 3, 4, 5];
  protected readonly statuses: readonly WatchStatus[] = [
    'pending',
    'in_progress',
    'completed',
    'abandoned',
    'unavailable',
  ];

  protected readonly outstanding = computed(() => this.items().filter((i) => this.isOutstanding(i)));
  protected readonly done = computed(() => this.items().filter((i) => !this.isOutstanding(i)));

  constructor() {
    this.load();
  }

  protected isOutstanding(item: WatchlistItem): boolean {
    return item.status === 'pending' || item.status === 'in_progress' || item.status === 'unavailable';
  }

  protected priorityLabel(priority: number): string {
    return PRIORITY_LABELS[priority] ?? '';
  }

  protected statusLabel(status: WatchStatus): string {
    return STATUS_LABELS[status];
  }

  protected formatMinutes(minutes: number): string {
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }

  protected setPriority(item: WatchlistItem, priority: number): void {
    if (priority === item.priority) return;
    this.patch(item, { priority });
  }

  protected setStatus(item: WatchlistItem, status: WatchStatus): void {
    if (status === item.status) return;
    this.patch(item, { status });
  }

  protected remove(item: WatchlistItem): void {
    this.busy.set(item.id);
    this.watchlists.remove(item.id).subscribe({
      next: () => {
        this.items.update((items) => items.filter((i) => i.id !== item.id));
        this.busy.set(null);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.busy.set(null);
      },
    });
  }

  private patch(item: WatchlistItem, change: { priority?: number; status?: WatchStatus }): void {
    this.busy.set(item.id);
    this.watchlists.update(item.id, change).subscribe({
      next: (updated) => {
        this.items.update((items) => items.map((i) => (i.id === updated.id ? updated : i)));
        this.busy.set(null);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.busy.set(null);
      },
    });
  }

  private load(): void {
    this.watchlists.get().subscribe({
      next: (watchlist) => {
        this.items.set(watchlist.items);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.loading.set(false);
      },
    });
  }
}

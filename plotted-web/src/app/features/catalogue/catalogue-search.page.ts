import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged, of, switchMap, catchError } from 'rxjs';

import {
  DiscoverResult,
  Title,
} from '../../core/catalogue/catalogue.models';
import { CatalogueService } from '../../core/catalogue/catalogue.service';
import { messageFrom } from '../../core/error/problem-detail';
import { TitleCardComponent } from './title-card.component';

/**
 * One box, two searches.
 *
 * Typing searches the local catalogue as you go — fast, and every result can be
 * opened. When the local results are thin, a second section offers TMDB results
 * that are one click from being ingested. The distinction is kept visible
 * because it is real: a discover result has no Plotted identifier yet, so
 * nothing else in the product can refer to it.
 */
@Component({
  selector: 'plotted-catalogue-search',
  standalone: true,
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatButtonModule,
    MatCardModule,
    MatProgressBarModule,
    TitleCardComponent,
  ],
  template: `
    <h1>Find something</h1>

    <mat-form-field appearance="outline" class="search-box">
      <mat-label>Search films and series</mat-label>
      <input
        matInput
        type="search"
        [formControl]="query"
        placeholder="Try &quot;severance&quot; — typos are fine"
        autocomplete="off"
      />
      <mat-icon matSuffix>search</mat-icon>
    </mat-form-field>

    @if (searching()) {
      <mat-progress-bar mode="indeterminate" aria-label="Searching" />
    }

    @if (error(); as message) {
      <p class="form-error" role="alert">{{ message }}</p>
    }

    @if (results().length > 0) {
      <section aria-label="In the catalogue">
        <div class="results-grid">
          @for (title of results(); track title.id) {
            <plotted-title-card [title]="title" />
          }
        </div>
      </section>
    }

    @if (showEmptyState()) {
      <p class="empty">Nothing in the catalogue matches "{{ query.value }}".</p>
    }

    @if (query.value && query.value.length >= 2 && !searching()) {
      <section class="discover" aria-label="From TMDB">
        <h2>Not finding it?</h2>
        @if (!discovered()) {
          <button mat-stroked-button type="button" (click)="discover()" [disabled]="discovering()">
            <mat-icon>travel_explore</mat-icon>
            Search TMDB for "{{ query.value }}"
          </button>
        }
        @if (discovering()) {
          <mat-progress-bar mode="indeterminate" aria-label="Searching TMDB" />
        }
        @if (discovered(); as found) {
          @if (found.length === 0) {
            <p class="empty">TMDB has nothing for that either.</p>
          } @else {
            <ul class="discover-list">
              @for (result of found; track result.externalId) {
                <li>
                  <div class="discover-row">
                    <div>
                      <strong>{{ result.name }}</strong>
                      <span class="meta">
                        {{ result.mediaType === 'movie' ? 'Film' : 'Series' }}
                        @if (result.releaseDate; as date) {
                          &middot; {{ date | date: 'yyyy' }}
                        }
                      </span>
                      @if (result.overview; as overview) {
                        <p class="overview">{{ overview }}</p>
                      }
                    </div>
                    <button
                      mat-flat-button
                      color="primary"
                      type="button"
                      (click)="ingest(result)"
                      [disabled]="ingesting() === result.externalId"
                    >
                      {{ ingesting() === result.externalId ? 'Adding…' : 'Add to Plotted' }}
                    </button>
                  </div>
                </li>
              }
            </ul>
          }
        }
        <p class="attribution">Search results from TMDB. Not endorsed or certified by TMDB.</p>
      </section>
    }
  `,
  styles: `
    .search-box {
      width: 100%;
      max-width: 32rem;
    }

    .results-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
      gap: 1rem;
      margin-top: 1rem;
    }

    .empty {
      opacity: 0.7;
      margin: 1.5rem 0;
    }

    .discover {
      margin-top: 2.5rem;
      padding-top: 1rem;
      border-top: 1px solid var(--plotted-border);

      h2 {
        font-size: 1.1rem;
      }
    }

    .discover-list {
      list-style: none;
      padding: 0;
      margin: 1rem 0;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .discover-row {
      display: flex;
      justify-content: space-between;
      align-items: start;
      gap: 1rem;

      .meta {
        margin-left: 0.5rem;
        font-size: 0.8rem;
        opacity: 0.7;
      }

      .overview {
        font-size: 0.85rem;
        opacity: 0.8;
        margin: 0.25rem 0 0;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
        max-width: 40rem;
      }

      button {
        flex-shrink: 0;
      }
    }

    .attribution {
      font-size: 0.72rem;
      opacity: 0.6;
      margin-top: 1rem;
    }

    .form-error {
      color: var(--plotted-critical);
    }
  `,
})
export class CatalogueSearchPage implements OnInit {
  private readonly catalogue = inject(CatalogueService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly query = new FormControl('', { nonNullable: true });
  protected readonly results = signal<Title[]>([]);
  protected readonly searching = signal(false);
  protected readonly discovering = signal(false);
  protected readonly discovered = signal<DiscoverResult[] | null>(null);
  protected readonly ingesting = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected showEmptyState(): boolean {
    return (
      !this.searching() && this.query.value.trim().length >= 2 && this.results().length === 0
    );
  }

  ngOnInit(): void {
    this.query.valueChanges
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((value) => {
          // A new query invalidates old TMDB results; do not leave them
          // standing beside local results for a different search.
          this.discovered.set(null);
          this.error.set(null);
          const trimmed = value.trim();
          if (trimmed.length < 2) {
            this.searching.set(false);
            return of<Title[]>([]);
          }
          this.searching.set(true);
          return this.catalogue.search(trimmed).pipe(
            catchError((failure: unknown) => {
              this.error.set(messageFrom(failure, 'Search failed.'));
              return of<Title[]>([]);
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((titles) => {
        this.results.set(titles);
        this.searching.set(false);
      });
  }

  protected discover(): void {
    const trimmed = this.query.value.trim();
    if (trimmed.length < 2 || this.discovering()) return;
    this.discovering.set(true);
    this.error.set(null);

    this.catalogue.discover(trimmed).subscribe({
      next: (found) => {
        this.discovered.set(found);
        this.discovering.set(false);
      },
      error: (failure: unknown) => {
        this.discovering.set(false);
        this.error.set(messageFrom(failure, 'TMDB could not be reached.'));
      },
    });
  }

  protected ingest(result: DiscoverResult): void {
    if (this.ingesting()) return;
    this.ingesting.set(result.externalId);
    this.error.set(null);

    this.catalogue
      .ingest({ mediaType: result.mediaType, tmdbId: Number(result.externalId) })
      .subscribe({
        next: (title) => {
          // Straight to the title page: the point of adding it is to look at it.
          void this.router.navigate(['/titles', title.id]);
        },
        error: (failure: unknown) => {
          this.ingesting.set(null);
          this.error.set(messageFrom(failure, 'Could not add the title.'));
        },
      });
  }
}

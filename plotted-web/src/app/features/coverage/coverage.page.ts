import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import { Coverage } from '../../core/watchlist/watchlist.models';
import { WatchlistService } from '../../core/watchlist/watchlist.service';

/**
 * Which service covers the most of the watchlist.
 *
 * Two things this screen refuses to do, both of which would make it look more
 * confident and be less honest: it does not present shares as though they add up
 * to 100% (a title on two services counts for both, because the question is
 * "what would this one subscription get me"), and it does not fold unchecked
 * titles into the denominator, which would blame a service for gaps in Plotted's
 * own data. The unchecked count is shown instead.
 */
@Component({
  selector: 'plotted-coverage',
  standalone: true,
  imports: [
    DecimalPipe,
    RouterLink,
    MatButtonModule,
    MatExpansionModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <section class="page">
      <header class="head">
        <h1>Coverage</h1>
        <p class="sub">
          How much of what you still want to watch each service carries, weighted by how much you
          want it. A title on two services counts for both, so these do not add up to 100%.
        </p>
      </header>

      @if (loading()) {
        <div class="centre"><mat-spinner diameter="36" /></div>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else {
        @if (report(); as data) {
          @if (data.consideredTitles === 0) {
          <div class="empty">
            <mat-icon>insights</mat-icon>
            <h2>Nothing to measure yet</h2>
            @if (data.unknownTitles > 0) {
              <!-- The distinction that matters: there IS a list, Plotted just has
                   not checked any of it. Saying "0% covered" here would be a
                   statement about the services, and it would be false. -->
              <p>
                {{ data.unknownTitles }}
                {{ data.unknownTitles === 1 ? 'title has' : 'titles have' }} never had their
                availability checked, so there is nothing to score yet. This resolves once the
                catalogue has been refreshed.
              </p>
            } @else {
              <p>Add a few things to your list and this will tell you which service to keep.</p>
              <a mat-flat-button routerLink="/watchlist">Go to your list</a>
            }
          </div>
        } @else {
          <p class="basis">
            Measured across <strong>{{ data.consideredTitles }}</strong>
            {{ data.consideredTitles === 1 ? 'title' : 'titles' }} you have not watched yet.
            @if (data.unknownTitles > 0) {
              <span class="caveat">
                {{ data.unknownTitles }} more
                {{ data.unknownTitles === 1 ? 'is' : 'are' }} not counted, because nobody has
                checked where {{ data.unknownTitles === 1 ? 'it is' : 'they are' }} available yet.
              </span>
            }
          </p>

          <mat-accordion class="providers">
            @for (provider of data.providers; track provider.providerId) {
              <mat-expansion-panel>
                <mat-expansion-panel-header>
                  <mat-panel-title>
                    <div class="provider">
                      @if (provider.logoUrl) {
                        <img [src]="provider.logoUrl" [alt]="''" aria-hidden="true" />
                      }
                      <span class="provider-name">{{ provider.name }}</span>
                    </div>
                  </mat-panel-title>
                  <mat-panel-description>
                    <div class="score">
                      <div class="bar" aria-hidden="true">
                        <span [style.width.%]="provider.weightedShare * 100"></span>
                      </div>
                      <span class="pct">{{ provider.weightedShare * 100 | number: '1.0-0' }}%</span>
                      <span class="count">
                        {{ provider.titleCount }}
                        {{ provider.titleCount === 1 ? 'title' : 'titles' }}
                      </span>
                    </div>
                  </mat-panel-description>
                </mat-expansion-panel-header>

                <!-- The working, shown. A percentage nobody can drill into is a
                     number the user has to take on trust, and this one is about
                     to be used to decide where money goes. -->
                <ul class="titles">
                  @for (title of provider.titles; track title.titleId) {
                    <li>
                      <span class="priority" [attr.title]="'Priority ' + title.priority">{{
                        title.priority
                      }}</span>
                      <a [routerLink]="['/titles', title.titleId]">{{
                        title.name ?? 'Untitled'
                      }}</a>
                    </li>
                  }
                </ul>
              </mat-expansion-panel>
            }
          </mat-accordion>

            <p class="attribution">{{ data.attribution }}</p>
          }
        }
      }
    </section>
  `,
  styles: `
    .page {
      max-width: 52rem;
      margin: 0 auto;
      padding: 1.5rem 1rem 3rem;
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
      max-width: 38rem;
    }

    .basis {
      font-size: 0.85rem;
      color: var(--plotted-text-muted, var(--plotted-text-faint));
      margin: 0 0 1rem;
    }

    .caveat {
      display: block;
      margin-top: 0.3rem;
      color: var(--plotted-text-faint);
      font-style: italic;
    }

    .provider {
      display: flex;
      align-items: center;
      gap: 0.6rem;

      img {
        width: 1.5rem;
        height: 1.5rem;
        border-radius: 4px;
      }
    }

    .provider-name {
      font-weight: 600;
    }

    .score {
      display: flex;
      align-items: center;
      gap: 0.7rem;
      width: 100%;
      justify-content: flex-end;
    }

    .bar {
      flex: 1;
      max-width: 12rem;
      height: 6px;
      border-radius: 3px;
      background: var(--plotted-surface-raised);
      overflow: hidden;

      span {
        display: block;
        height: 100%;
        background: var(--plotted-accent);
      }
    }

    .pct {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
      min-width: 2.7rem;
      text-align: right;
    }

    .count {
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
      min-width: 4.5rem;
      text-align: right;
    }

    .titles {
      list-style: none;
      margin: 0;
      padding: 0;

      li {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        padding: 0.3rem 0;
        font-size: 0.88rem;
      }

      a {
        color: inherit;
        text-decoration: none;
      }

      a:hover {
        text-decoration: underline;
      }
    }

    .priority {
      display: grid;
      place-items: center;
      width: 1.4rem;
      height: 1.4rem;
      border-radius: 4px;
      background: var(--plotted-surface-raised);
      font-size: 0.72rem;
      font-weight: 600;
      color: var(--plotted-text-faint);
      flex-shrink: 0;
    }

    .attribution {
      margin-top: 1.5rem;
      font-size: 0.72rem;
      color: var(--plotted-text-faint);
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
        max-width: 30rem;
        margin: 0 auto 1.25rem;
        font-size: 0.88rem;
      }
    }

    .error {
      color: var(--plotted-danger);
      font-size: 0.88rem;
    }
  `,
})
export class CoveragePage {
  private readonly watchlists = inject(WatchlistService);

  protected readonly report = signal<Coverage | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.watchlists.coverage().subscribe({
      next: (report) => {
        this.report.set(report);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.loading.set(false);
      },
    });
  }
}

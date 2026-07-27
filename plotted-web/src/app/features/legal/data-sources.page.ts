import { Component } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';

interface DataSource {
  need: string;
  source: string;
  terms: string;
  cadence: string;
}

/**
 * Required by the attribution and compliance checklist (spec section 7.4).
 * Third-party data comes with obligations, and stating them is both the correct
 * thing to do and the cheapest way to show the obligations were noticed.
 */
@Component({
  selector: 'plotted-data-sources',
  standalone: true,
  imports: [MatCardModule, MatTableModule],
  template: `
    <h1>Where Plotted's data comes from</h1>
    <p class="lede">
      Everything Plotted claims about a title comes from somewhere, and every source comes with
      obligations. This is the record of both.
    </p>

    <div class="scroll">
      <table mat-table [dataSource]="sources" class="sources">
        <ng-container matColumnDef="need">
          <th mat-header-cell *matHeaderCellDef scope="col">What it is used for</th>
          <td mat-cell *matCellDef="let row">{{ row.need }}</td>
        </ng-container>

        <ng-container matColumnDef="source">
          <th mat-header-cell *matHeaderCellDef scope="col">Source</th>
          <td mat-cell *matCellDef="let row">{{ row.source }}</td>
        </ng-container>

        <ng-container matColumnDef="terms">
          <th mat-header-cell *matHeaderCellDef scope="col">Terms</th>
          <td mat-cell *matCellDef="let row">{{ row.terms }}</td>
        </ng-container>

        <ng-container matColumnDef="cadence">
          <th mat-header-cell *matHeaderCellDef scope="col">Refresh</th>
          <td mat-cell *matCellDef="let row">{{ row.cadence }}</td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
    </div>

    <mat-card class="note">
      <mat-card-content>
        <h2>Three things Plotted will not do</h2>
        <p>
          <strong>It does not scrape provider websites.</strong> Scraping violates terms of
          service, breaks constantly, and is the wrong answer to a data problem.
        </p>
        <p>
          <strong>It does not show removal dates.</strong> No public feed publishes reliable
          forward-looking removal dates. Plotted detects changes that have already happened by
          diffing nightly snapshots, and reports removal risk as a probability rather than
          inventing a date.
        </p>
        <p>
          <strong>It does not hide when it might be wrong.</strong> Availability data is
          imperfect, particularly for smaller Canadian services. Every availability claim shows
          its source, region and last-verified time, and you can report one that is wrong.
        </p>
      </mat-card-content>
    </mat-card>

    <p class="attribution">
      This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming
      availability data provided by JustWatch.
    </p>
  `,
  styles: `
    .lede {
      max-width: 44rem;
    }

    .scroll {
      overflow-x: auto;
      margin: 1.5rem 0;
    }

    .sources {
      width: 100%;
      min-width: 44rem;
    }

    .note {
      max-width: 48rem;
    }

    .note h2 {
      font-size: 1.1rem;
    }

    .attribution {
      margin-top: 1.5rem;
      font-size: 0.8rem;
      opacity: 0.75;
    }
  `,
})
export class DataSourcesPage {
  protected readonly columns = ['need', 'source', 'terms', 'cadence'];

  protected readonly sources: DataSource[] = [
    {
      need: 'Title metadata, posters, cast, genres, seasons and episodes',
      source: 'TMDB API',
      terms: 'Free for non-commercial use. Attribution required. Must not imply endorsement.',
      cadence: 'Watchlist titles daily, catalogue weekly',
    },
    {
      need: 'Regional streaming availability',
      source: 'TMDB /watch/providers, powered by JustWatch',
      terms: 'Included with TMDB. Attribution required. No redistribution as a dataset.',
      cadence: 'Watchlist titles daily',
    },
    {
      need: 'Rental and purchase pricing',
      source: 'Watchmode or a JustWatch partner API',
      terms: 'Freemium, low free quotas',
      cadence: 'On demand, cached',
    },
    {
      need: 'Ratings and popularity priors, and model bootstrapping',
      source: 'TMDB and MovieLens 32M',
      terms: 'MovieLens is research-use, non-commercial, citation required',
      cadence: 'Weekly, and one-off for MovieLens',
    },
    {
      need: 'Provider plan pricing',
      source: 'Public pricing pages, entered by hand',
      terms: 'Manually curated and versioned',
      cadence: 'Manual, with the date checked recorded',
    },
    {
      need: 'Viewing history import',
      source: 'Your own Netflix or Prime data export',
      terms: 'Supplied by you, deleted when you disconnect the account',
      cadence: 'On request',
    },
  ];
}

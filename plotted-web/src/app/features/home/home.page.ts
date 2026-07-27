import { DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatListModule } from '@angular/material/list';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'plotted-home',
  standalone: true,
  imports: [DatePipe, RouterLink, MatCardModule, MatButtonModule, MatListModule],
  template: `
    @if (auth.user(); as user) {
      <section class="greeting">
        <h1>Evening, {{ user.displayName }}.</h1>
        <p>
          Your account is set up for {{ user.regionCode }} and priced in
          {{ user.preferredCurrency }}. Joined {{ user.createdAt | date: 'longDate' }}.
        </p>
      </section>

      <div class="cards">
        <mat-card>
          <mat-card-header>
            <mat-card-title>Account</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <mat-list>
              <mat-list-item>
                <span matListItemTitle>Email</span>
                <span matListItemLine>{{ user.email }}</span>
              </mat-list-item>
              <mat-list-item>
                <span matListItemTitle>Time zone</span>
                <span matListItemLine>{{ user.timezone }}</span>
              </mat-list-item>
              <mat-list-item>
                <span matListItemTitle>Onboarding</span>
                <span matListItemLine>{{ user.onboardingStatus }}</span>
              </mat-list-item>
            </mat-list>
          </mat-card-content>
          <mat-card-actions>
            <a mat-button routerLink="/settings">Edit your defaults</a>
          </mat-card-actions>
        </mat-card>

        <mat-card>
          <mat-card-header>
            <mat-card-title>What is built so far</mat-card-title>
            <mat-card-subtitle>Phase 1 of 12 &mdash; the skeleton</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <p>
              Accounts, sessions, the Canadian schema and the recommendation defaults below are
              live. The catalogue lands in phase 2, watchlists and coverage in phase 3, then
              Tonight Mode and the subscription optimiser.
            </p>
            <p class="muted">
              There is nothing to recommend yet, so nothing here pretends to recommend anything.
            </p>
          </mat-card-content>
        </mat-card>
      </div>
    }
  `,
  styles: `
    .greeting h1 {
      margin-bottom: 0.25rem;
    }

    .cards {
      display: grid;
      gap: 1rem;
      grid-template-columns: repeat(auto-fit, minmax(20rem, 1fr));
      margin-top: 1.5rem;
    }

    .muted {
      opacity: 0.7;
      font-size: 0.875rem;
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
}

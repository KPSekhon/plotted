import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

import { Alert } from '../../core/alerts/alerts.models';
import { AlertsService } from '../../core/alerts/alerts.service';

/**
 * Unread alerts, or nothing at all.
 *
 * Renders no heading and no empty state when there is nothing to say. Plot
 * Armour's whole design is about suppressing alerts that are not worth sending,
 * and a permanent "no alerts" panel would put the feature back on screen every
 * day for the exact case it works hardest to produce.
 *
 * Dismiss is offered next to every alert rather than buried. Somebody who
 * dismisses one is answering, and the API takes that as a 60-day answer for that
 * title — so it has to be easy to give.
 */
@Component({
  selector: 'plotted-alert-list',
  standalone: true,
  imports: [RouterLink, MatButtonModule, MatIconModule],
  template: `
    @if (alerts().length > 0) {
      <section class="alerts" aria-label="Alerts">
        @for (alert of alerts(); track alert.id) {
          <article class="alert" [class]="alert.severity">
            <mat-icon aria-hidden="true">{{ icon(alert.severity) }}</mat-icon>
            <p class="message">
              @if (alert.titleId) {
                <a [routerLink]="['/titles', alert.titleId]">{{ alert.message }}</a>
              } @else {
                {{ alert.message }}
              }
            </p>
            <button
              mat-icon-button
              [disabled]="busy() === alert.id"
              (click)="dismiss(alert)"
              [attr.aria-label]="'Dismiss: ' + alert.message"
            >
              <mat-icon>close</mat-icon>
            </button>
          </article>
        }
      </section>
    }
  `,
  styles: `
    .alerts {
      display: grid;
      gap: 0.5rem;
      margin-bottom: 1.5rem;
    }

    .alert {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 0.6rem;
      align-items: center;
      padding: 0.6rem 0.75rem;
      border-radius: 0.6rem;
      background: var(--plotted-surface-raised);
      border-left: 3px solid var(--plotted-text-faint);
    }

    .alert.warning {
      border-left-color: var(--plotted-accent, #ffb300);
    }

    .alert.urgent {
      border-left-color: var(--mat-sys-error, #b3261e);
    }

    .message {
      margin: 0;
      font-size: 0.9rem;
    }

    .message a {
      color: inherit;
    }
  `,
})
export class AlertListComponent implements OnInit {
  private readonly alertsApi = inject(AlertsService);

  protected readonly alerts = signal<readonly Alert[]>([]);
  protected readonly busy = signal<string | null>(null);

  ngOnInit(): void {
    this.alertsApi.unread().subscribe({
      next: (response) => this.alerts.set(response.alerts),
      // Alerts failing must not break the page they sit on. Nothing here is
      // load-bearing enough to be worth an error banner.
      error: () => this.alerts.set([]),
    });
  }

  protected dismiss(alert: Alert): void {
    this.busy.set(alert.id);
    this.alertsApi.settle(alert.id, { status: 'dismissed' }).subscribe({
      next: () => {
        this.alerts.update((current) => current.filter((it) => it.id !== alert.id));
        this.busy.set(null);
      },
      // Left on screen rather than removed optimistically: an alert that
      // reappears on the next load is worse than one that did not go away.
      error: () => this.busy.set(null),
    });
  }

  protected icon(severity: string): string {
    return severity === 'urgent' ? 'priority_high' : 'info';
  }
}

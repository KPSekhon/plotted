import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import { messageFrom } from '../../core/error/problem-detail';
import {
  BILLING_PERIOD_LABELS,
  BillingPeriod,
  Provider,
  SUBSCRIPTION_STATUS_LABELS,
  Subscription,
  SubscriptionStatus,
} from '../../core/subscriptions/subscriptions.models';
import { SubscriptionsService } from '../../core/subscriptions/subscriptions.service';

/**
 * What the user pays for.
 *
 * The price field is empty by default and the user fills it in. Plotted ships no
 * pricing data on purpose: a number it invented would flow into the phase 5
 * optimiser and come back out as confident, wrong financial advice. What someone
 * tells us about their own bill is the most reliable figure available.
 */
@Component({
  selector: 'plotted-subscriptions',
  standalone: true,
  imports: [
    CurrencyPipe,
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  template: `
    <section class="page">
      <header class="head">
        <div>
          <h1>Subscriptions</h1>
          <p class="sub">
            What you pay, in your own numbers. Plotted does not look prices up — a price it
            guessed would end up in the cancellation advice.
          </p>
        </div>
      </header>

      @if (loading()) {
        <div class="centre"><mat-spinner diameter="36" /></div>
      } @else {
        @if (error(); as message) {
          <p class="error" role="alert">{{ message }}</p>
        }

        @if (subscriptions().length > 0) {
          <div class="total">
            <span class="amount">{{ monthlyTotal() | currency: currency() : 'symbol' }}</span>
            <span class="label">
              per month across {{ counted() }}
              {{ counted() === 1 ? 'service' : 'services' }}
            </span>
          </div>

          <div class="list">
            @for (item of subscriptions(); track item.id) {
              <article class="row" [class.inactive]="!isCurrent(item)">
                @if (item.providerLogoUrl) {
                  <img [src]="item.providerLogoUrl" alt="" aria-hidden="true" />
                } @else {
                  <div class="logo-fallback" aria-hidden="true"><mat-icon>live_tv</mat-icon></div>
                }

                <div class="detail">
                  <span class="name">{{ item.providerName }}</span>
                  <p class="meta">
                    <span>{{ item.planName }}</span>
                    <span>&middot; {{ billingLabel(item.billingPeriod) }}</span>
                    @if (item.billingPeriod !== 'monthly') {
                      <span class="derived">
                        &middot; {{ item.monthlyCost | currency: item.currency : 'symbol' }}/mo
                      </span>
                    }
                    @if (item.renewsOn) {
                      <span>&middot; renews {{ item.renewsOn | date: 'mediumDate' }}</span>
                    }
                    @if (item.cannotCancel) {
                      <!-- Flagged prominently because phase 5 treats it as a hard
                           constraint: advising someone to cancel something they
                           cannot cancel discredits every other suggestion. -->
                      <span
                        class="locked"
                        [matTooltip]="
                          item.commitmentEndsOn
                            ? 'Committed until ' + item.commitmentEndsOn
                            : 'Marked as not cancellable'
                        "
                      >
                        &middot; locked in
                      </span>
                    }
                  </p>
                </div>

                <span class="price">{{ item.price | currency: item.currency : 'symbol' }}</span>

                <mat-select
                  class="status"
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
                  matTooltip="Forget this subscription"
                  [attr.aria-label]="'Forget ' + item.providerName"
                >
                  <mat-icon>delete_outline</mat-icon>
                </button>
              </article>
            }
          </div>
        } @else {
          <div class="empty">
            <mat-icon>credit_card</mat-icon>
            <h2>No subscriptions recorded</h2>
            <p>Add what you pay for and Plotted can start telling you what it is worth.</p>
          </div>
        }

        <form class="add" (ngSubmit)="add()">
          <h2>Add a subscription</h2>
          <div class="fields">
            <mat-form-field appearance="outline">
              <mat-label>Service</mat-label>
              <mat-select [(ngModel)]="draftProviderId" name="providerId" required>
                @for (provider of providers(); track provider.id) {
                  <mat-option [value]="provider.id">{{ provider.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Plan</mat-label>
              <input matInput [(ngModel)]="draftPlanName" name="planName" placeholder="Standard" />
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Price</mat-label>
              <input
                matInput
                type="number"
                min="0"
                step="0.01"
                [(ngModel)]="draftPrice"
                name="price"
                required
              />
              <span matTextPrefix>$&nbsp;</span>
              <mat-hint>What you actually pay, including any discount.</mat-hint>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Billed</mat-label>
              <mat-select [(ngModel)]="draftBillingPeriod" name="billingPeriod">
                @for (period of billingPeriods; track period) {
                  <mat-option [value]="period">{{ billingLabel(period) }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Renews on</mat-label>
              <input matInput type="date" [(ngModel)]="draftRenewsOn" name="renewsOn" />
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Committed until</mat-label>
              <input
                matInput
                type="date"
                [(ngModel)]="draftCommitmentEndsOn"
                name="commitmentEndsOn"
              />
              <mat-hint>Leave blank if you can cancel any time.</mat-hint>
            </mat-form-field>
          </div>

          <button mat-flat-button type="submit" [disabled]="!canAdd() || saving()">
            @if (saving()) {
              <mat-spinner diameter="18" />
            } @else {
              Add subscription
            }
          </button>
        </form>
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
      max-width: 36rem;
    }

    .total {
      display: flex;
      align-items: baseline;
      gap: 0.6rem;
      padding: 1rem 1.15rem;
      border: 1px solid var(--plotted-border);
      border-radius: 12px;
      background: var(--plotted-surface);
      margin-bottom: 1.25rem;
    }

    .amount {
      font-size: 1.7rem;
      font-weight: 700;
      letter-spacing: -0.02em;
      font-variant-numeric: tabular-nums;
      color: var(--plotted-accent);
    }

    .label {
      font-size: 0.85rem;
      color: var(--plotted-text-faint);
    }

    .row {
      display: grid;
      grid-template-columns: 2rem 1fr auto 8.5rem 2.5rem;
      gap: 0.85rem;
      align-items: center;
      padding: 0.6rem;
      border: 1px solid var(--plotted-border);
      border-radius: 10px;
      background: var(--plotted-surface);
      margin-bottom: 0.5rem;
    }

    .row.inactive {
      opacity: 0.6;
    }

    img,
    .logo-fallback {
      width: 2rem;
      height: 2rem;
      border-radius: 5px;
      display: block;
    }

    .logo-fallback {
      display: grid;
      place-items: center;
      background: var(--plotted-surface-raised);
      color: var(--plotted-text-faint);
    }

    .name {
      font-weight: 600;
      font-size: 0.95rem;
    }

    .meta {
      margin: 0.15rem 0 0;
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
      display: flex;
      gap: 0.25rem;
      flex-wrap: wrap;
    }

    .derived {
      font-variant-numeric: tabular-nums;
    }

    .locked {
      color: var(--plotted-accent);
      font-weight: 600;
    }

    .price {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
    }

    .add {
      margin-top: 2.5rem;
      padding-top: 1.5rem;
      border-top: 1px solid var(--plotted-border);

      h2 {
        font-size: 1rem;
        margin: 0 0 1rem;
      }
    }

    .fields {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
      gap: 0.5rem 0.9rem;
      margin-bottom: 0.75rem;
    }

    .centre {
      display: grid;
      place-items: center;
      padding: 3rem;
    }

    .empty {
      text-align: center;
      padding: 2.5rem 1rem;
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
        margin: 0;
        font-size: 0.88rem;
      }
    }

    .error {
      color: var(--plotted-danger);
      font-size: 0.88rem;
    }
  `,
})
export class SubscriptionsPage {
  private readonly service = inject(SubscriptionsService);

  protected readonly subscriptions = signal<readonly Subscription[]>([]);
  protected readonly providers = signal<readonly Provider[]>([]);
  protected readonly monthlyTotal = signal(0);
  protected readonly currency = signal('CAD');
  protected readonly counted = signal(0);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);

  protected readonly billingPeriods: readonly BillingPeriod[] = ['monthly', 'quarterly', 'annual'];
  protected readonly statuses: readonly SubscriptionStatus[] = [
    'active',
    'trial',
    'paused',
    'cancelled',
    'lapsed',
  ];

  protected draftProviderId = '';
  protected draftPlanName = '';
  protected draftPrice: number | null = null;
  protected draftBillingPeriod: BillingPeriod = 'monthly';
  protected draftRenewsOn = '';
  protected draftCommitmentEndsOn = '';

  constructor() {
    this.load();
    this.service.providers().subscribe({
      next: (providers) => this.providers.set(providers),
      error: (failure: unknown) => this.error.set(messageFrom(failure)),
    });
  }

  protected isCurrent(item: Subscription): boolean {
    return item.status === 'active' || item.status === 'trial';
  }

  protected billingLabel(period: BillingPeriod): string {
    return BILLING_PERIOD_LABELS[period];
  }

  protected statusLabel(status: SubscriptionStatus): string {
    return SUBSCRIPTION_STATUS_LABELS[status];
  }

  protected canAdd(): boolean {
    return this.draftProviderId !== '' && this.draftPrice !== null && this.draftPrice >= 0;
  }

  protected add(): void {
    if (!this.canAdd()) return;
    this.saving.set(true);
    this.error.set(null);
    this.service
      .add({
        providerId: this.draftProviderId,
        planName: this.draftPlanName || undefined,
        price: this.draftPrice as number,
        billingPeriod: this.draftBillingPeriod,
        renewsOn: this.draftRenewsOn || undefined,
        commitmentEndsOn: this.draftCommitmentEndsOn || undefined,
      })
      .subscribe({
        next: () => {
          this.resetDraft();
          this.saving.set(false);
          this.load();
        },
        error: (failure: unknown) => {
          this.error.set(messageFrom(failure));
          this.saving.set(false);
        },
      });
  }

  protected setStatus(item: Subscription, status: SubscriptionStatus): void {
    if (status === item.status) return;
    this.busy.set(item.id);
    this.service.update(item.id, { status }).subscribe({
      next: () => {
        this.busy.set(null);
        // Reloaded rather than patched in place: the monthly total depends on
        // which rows count, so a status change moves a number this component
        // does not own the arithmetic for.
        this.load();
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.busy.set(null);
      },
    });
  }

  protected remove(item: Subscription): void {
    this.busy.set(item.id);
    this.service.remove(item.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.load();
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.busy.set(null);
      },
    });
  }

  private resetDraft(): void {
    this.draftProviderId = '';
    this.draftPlanName = '';
    this.draftPrice = null;
    this.draftBillingPeriod = 'monthly';
    this.draftRenewsOn = '';
    this.draftCommitmentEndsOn = '';
  }

  private load(): void {
    this.service.list().subscribe({
      next: (list) => {
        this.subscriptions.set(list.subscriptions);
        this.monthlyTotal.set(list.monthlyTotal);
        this.currency.set(list.currency);
        this.counted.set(list.countedSubscriptions);
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.error.set(messageFrom(failure));
        this.loading.set(false);
      },
    });
  }
}

import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

import { UserSettings, UserSettingsPatch } from '../../core/auth/auth.models';
import { messageFrom } from '../../core/error/problem-detail';
import { UserSettingsService } from '../../core/user/user-settings.service';

/**
 * These values are the defaults Tonight Mode will start from. Every one of them
 * exists so the fast path -- open, confirm, get three cards -- does not have to
 * ask a question it could have answered from settings.
 */
@Component({
  selector: 'plotted-settings',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <h1>Your defaults</h1>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" aria-label="Loading settings" />
    }

    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-card>
        <mat-card-header>
          <mat-card-title>Budget</mat-card-title>
          <mat-card-subtitle>
            The constraints the subscription optimiser will solve against.
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content class="grid">
          <mat-form-field appearance="outline">
            <mat-label>Monthly budget</mat-label>
            <span matTextPrefix>$&nbsp;</span>
            <input matInput type="number" step="0.01" min="0" formControlName="maximumMonthlyBudget" />
            <mat-hint>Leave empty for no budget ceiling.</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Maximum active services</mat-label>
            <input matInput type="number" min="0" max="20" formControlName="maximumActiveServices" />
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Maximum changes per month</mat-label>
            <input matInput type="number" min="0" max="20" formControlName="maximumMonthlySwitches" />
            <mat-hint>Starting and cancelling services is effort; this caps it.</mat-hint>
          </mat-form-field>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header>
          <mat-card-title>Tonight</mat-card-title>
          <mat-card-subtitle>Pre-filled whenever you ask for a recommendation.</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content class="grid">
          <mat-form-field appearance="outline">
            <mat-label>Usual time available</mat-label>
            <input matInput type="number" min="5" max="1440" formControlName="defaultAvailableMinutes" />
            <span matTextSuffix>min</span>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Access policy</mat-label>
            <mat-select formControlName="defaultAccessPolicy">
              <mat-option value="active_subscriptions_only">
                Only what I already pay for
              </mat-option>
              <mat-option value="include_free">Include free and ad-supported</mat-option>
              <mat-option value="all_access">Anything, including rentals</mat-option>
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Commitment</mat-label>
            <mat-select formControlName="defaultCommitmentPreference">
              <mat-option value="low">Low &mdash; nothing I have to keep up with</mat-option>
              <mat-option value="medium">Medium</mat-option>
              <mat-option value="high">High &mdash; happy to start a long series</mat-option>
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Appetite for something new</mat-label>
            <input
              matInput
              type="number"
              step="0.05"
              min="0"
              max="1"
              formControlName="defaultNoveltyPreference"
            />
            <mat-hint>0 is comfort viewing, 1 is always something unfamiliar.</mat-hint>
          </mat-form-field>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header>
          <mat-card-title>Paying extra</mat-card-title>
        </mat-card-header>
        <mat-card-content class="grid">
          <mat-slide-toggle formControlName="allowPaidRentals">Allow rentals</mat-slide-toggle>

          <mat-form-field appearance="outline">
            <mat-label>Maximum rental price</mat-label>
            <span matTextPrefix>$&nbsp;</span>
            <input matInput type="number" step="0.01" min="0" formControlName="maximumRentalPrice" />
          </mat-form-field>

          <mat-slide-toggle formControlName="allowPhysicalMedia">
            Consider discs and library copies
          </mat-slide-toggle>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header>
          <mat-card-title>Viewing capacity</mat-card-title>
          <mat-card-subtitle>
            Plotted estimates this from the last eight weeks. Override it if the estimate is
            wrong &mdash; it decides whether you could realistically finish a series before a
            renewal.
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content class="grid">
          <mat-form-field appearance="outline">
            <mat-label>Weekly viewing time</mat-label>
            <input
              matInput
              type="number"
              min="0"
              max="10080"
              formControlName="weeklyViewingMinutesOverride"
            />
            <span matTextSuffix>min / week</span>
            <mat-hint>Leave empty to use the estimate.</mat-hint>
          </mat-form-field>
        </mat-card-content>
      </mat-card>

      @if (error(); as message) {
        <p class="form-error" role="alert">{{ message }}</p>
      }
      @if (saved()) {
        <p class="form-saved" role="status">Saved.</p>
      }

      <button mat-flat-button color="primary" type="submit" [disabled]="saving() || loading()">
        Save defaults
      </button>
    </form>
  `,
  styles: `
    form {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      max-width: 52rem;
    }

    .grid {
      display: grid;
      gap: 1rem;
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
      align-items: center;
    }

    button[type='submit'] {
      align-self: flex-start;
    }

    .form-error {
      color: var(--plotted-critical);
    }

    .form-saved {
      opacity: 0.75;
    }
  `,
})
export class SettingsPage implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly settingsService = inject(UserSettingsService);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.formBuilder.group({
    maximumMonthlyBudget: this.formBuilder.control<number | null>(null, [Validators.min(0)]),
    maximumActiveServices: this.formBuilder.control<number | null>(null, [
      Validators.min(0),
      Validators.max(20),
    ]),
    maximumMonthlySwitches: this.formBuilder.control<number | null>(null, [
      Validators.min(0),
      Validators.max(20),
    ]),
    defaultAvailableMinutes: this.formBuilder.control<number | null>(null, [
      Validators.min(5),
      Validators.max(1440),
    ]),
    defaultAccessPolicy: this.formBuilder.control<string>('active_subscriptions_only'),
    defaultCommitmentPreference: this.formBuilder.control<string>('medium'),
    defaultNoveltyPreference: this.formBuilder.control<number>(0.4, [
      Validators.min(0),
      Validators.max(1),
    ]),
    allowPaidRentals: this.formBuilder.control<boolean>(false),
    maximumRentalPrice: this.formBuilder.control<number | null>(null, [Validators.min(0)]),
    allowPhysicalMedia: this.formBuilder.control<boolean>(false),
    weeklyViewingMinutesOverride: this.formBuilder.control<number | null>(null, [
      Validators.min(0),
      Validators.max(10080),
    ]),
  });

  ngOnInit(): void {
    this.settingsService.get().subscribe({
      next: (settings) => {
        this.form.patchValue(this.toForm(settings));
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        this.loading.set(false);
        this.error.set(messageFrom(failure, 'Could not load your settings.'));
      },
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);

    this.settingsService.patch(this.toPatch()).subscribe({
      next: (settings) => {
        this.form.patchValue(this.toForm(settings));
        this.saving.set(false);
        this.saved.set(true);
      },
      error: (failure: unknown) => {
        this.saving.set(false);
        this.error.set(messageFrom(failure, 'Could not save your settings.'));
      },
    });
  }

  private toForm(settings: UserSettings) {
    return {
      maximumMonthlyBudget: settings.maximumMonthlyBudget,
      maximumActiveServices: settings.maximumActiveServices,
      maximumMonthlySwitches: settings.maximumMonthlySwitches,
      defaultAvailableMinutes: settings.defaultAvailableMinutes,
      defaultAccessPolicy: settings.defaultAccessPolicy,
      defaultCommitmentPreference: settings.defaultCommitmentPreference,
      defaultNoveltyPreference: Number(settings.defaultNoveltyPreference),
      allowPaidRentals: settings.allowPaidRentals,
      maximumRentalPrice: settings.maximumRentalPrice,
      allowPhysicalMedia: settings.allowPhysicalMedia,
      weeklyViewingMinutesOverride: settings.weeklyViewingMinutesOverride,
    };
  }

  /**
   * The API treats an omitted field as "leave alone", so nulls are stripped
   * rather than sent. Clearing a value is a separate action the API does not
   * expose yet, and pretending otherwise here would silently drop settings.
   */
  private toPatch(): UserSettingsPatch {
    const raw = this.form.getRawValue();
    return Object.fromEntries(
      Object.entries(raw).filter(([, value]) => value !== null && value !== ''),
    ) as UserSettingsPatch;
  }
}

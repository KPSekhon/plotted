import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { messageFrom } from '../../core/error/problem-detail';

@Component({
  selector: 'plotted-sign-up',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-card class="auth-card">
      <mat-card-header>
        <mat-card-title>Create an account</mat-card-title>
        <mat-card-subtitle>Canada only for now &mdash; deliberately.</mat-card-subtitle>
      </mat-card-header>

      @if (submitting()) {
        <mat-progress-bar mode="indeterminate" aria-label="Creating your account" />
      }

      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <mat-form-field appearance="outline">
            <mat-label>Display name</mat-label>
            <input matInput formControlName="displayName" autocomplete="name" required />
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Email</mat-label>
            <input matInput type="email" formControlName="email" autocomplete="email" required />
            @if (form.controls.email.touched && form.controls.email.invalid) {
              <mat-error>Enter a valid email address.</mat-error>
            }
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Password</mat-label>
            <input
              matInput
              type="password"
              formControlName="password"
              autocomplete="new-password"
              required
            />
            @if (form.controls.password.touched && form.controls.password.invalid) {
              <mat-error>Use at least 12 characters.</mat-error>
            }
          </mat-form-field>
          <p class="field-hint">
            At least 12 characters. Length beats punctuation &mdash; this password protects a
            record of what you watch.
          </p>

          @if (error(); as message) {
            <p class="form-error" role="alert">{{ message }}</p>
          }

          <button mat-flat-button color="primary" type="submit" [disabled]="submitting()">
            Create account
          </button>
        </form>
      </mat-card-content>

      <mat-card-actions>
        <span>Already have one?</span>
        <a mat-button routerLink="/sign-in">Sign in</a>
      </mat-card-actions>
    </mat-card>
  `,
  styleUrl: './auth.scss',
})
export class SignUpPage {
  private readonly formBuilder = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    displayName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    // Mirrors the server rule exactly. Where the two disagree, the server wins
    // and the message comes back in the Problem Detail's `errors` map.
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(200)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);

    this.auth
      .register({
        ...this.form.getRawValue(),
        regionCode: 'CA',
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      })
      .subscribe({
        next: () => void this.router.navigateByUrl('/'),
        error: (failure: unknown) => {
          this.submitting.set(false);
          this.error.set(messageFrom(failure, 'Could not create the account.'));
        },
      });
  }
}

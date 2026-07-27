import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { messageFrom } from '../../core/error/problem-detail';

@Component({
  selector: 'plotted-sign-in',
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
        <mat-card-title>Sign in</mat-card-title>
        <mat-card-subtitle>Pick up where your watchlist left off.</mat-card-subtitle>
      </mat-card-header>

      @if (submitting()) {
        <mat-progress-bar mode="indeterminate" aria-label="Signing in" />
      }

      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
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
              autocomplete="current-password"
              required
            />
          </mat-form-field>

          @if (error(); as message) {
            <p class="form-error" role="alert">{{ message }}</p>
          }

          <button mat-flat-button color="primary" type="submit" [disabled]="submitting()">
            Sign in
          </button>
        </form>
      </mat-card-content>

      <mat-card-actions>
        <span>New here?</span>
        <a mat-button routerLink="/sign-up">Create an account</a>
      </mat-card-actions>
    </mat-card>
  `,
  styleUrl: './auth.scss',
})
export class SignInPage {
  private readonly formBuilder = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        const next = this.route.snapshot.queryParamMap.get('next') ?? '/';
        void this.router.navigateByUrl(next);
      },
      error: (failure: unknown) => {
        this.submitting.set(false);
        // The server does not distinguish "no such account" from "wrong
        // password", and neither does this message.
        this.error.set(messageFrom(failure, 'Email or password is incorrect.'));
      },
    });
  }
}

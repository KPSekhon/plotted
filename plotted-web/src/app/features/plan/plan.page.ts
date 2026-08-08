import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import { messageFrom } from '../../core/error/problem-detail';
import { CONSTRAINT_LABELS, PlanResponse, Sensitivity } from '../../core/plan/plan.models';
import { PlanService } from '../../core/plan/plan.service';
import { PlottedIconComponent } from '../../shared/map/plotted-icon.component';
import { SectionNavComponent } from '../../shared/map/section-nav.component';
import { PlanTransitMapComponent } from './plan-transit-map.component';

/**
 * Cancel Culture.
 *
 * The screen is built around the three things the optimiser can say that a
 * recommendation engine normally cannot, and each one is given room rather than
 * tucked into a corner:
 *
 * - **A refusal.** No plan satisfies these limits, and here is which one is
 *   impossible. The limits were the request; quietly relaxing one to produce an
 *   answer would answer a different question.
 * - **A price on a constraint.** "One more service buys 14% of your list for
 *   $20.99 a month" is the most actionable sentence here, and it comes from
 *   re-solving rather than from a rule of thumb.
 * - **What it was never shown.** Titles that are free, unchecked, or only on a
 *   service with no established price never entered the model, and saying so is
 *   the difference between a coverage figure about the user's options and one
 *   about Plotted's data quality.
 *
 * Any `violations` are rendered first and loudly. They mean an independent
 * reimplementation of the rules disagreed with the solver's own plan, which is a
 * defect in the model — shown rather than hidden, because a wrong plan the user
 * can see beats one they cannot.
 */
@Component({
  selector: 'plotted-plan',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
    SectionNavComponent,
    PlottedIconComponent,
    PlanTransitMapComponent,
  ],
  template: `
    <section class="page">
      <plotted-section-nav />

      <header>
        <h1>Cancel Culture</h1>
        <p class="sub">
          Which subscriptions to keep, month by month, for what is actually on
          your list. Every limit below is honoured exactly &mdash; if they cannot
          all be met you get the reason, not a quietly relaxed plan.
        </p>
      </header>

      <!-- Once a plan exists it is the point of the screen, so five controls
           stop competing with it and collapse to a line stating what was asked.
           Reopening is one click, and the constraints stay visible as text
           because a plan read without knowing its limits is a plan misread. -->
      @if (result() && !editing()) {
        <div class="asked">
          <span class="coordinates">{{ constraintSummary() }}</span>
          <button type="button" class="link" (click)="editing.set(true)">Change constraints</button>
        </div>
      }

      <form class="controls" [class.hidden]="result() && !editing()" (ngSubmit)="ask()">
        <mat-form-field appearance="outline" class="narrow">
          <mat-label>Plan over</mat-label>
          <mat-select [(ngModel)]="horizonMonths" name="horizonMonths">
            @for (option of horizons; track option) {
              <mat-option [value]="option">{{ option }} months</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" class="narrow">
          <mat-label>Monthly budget</mat-label>
          <span matTextPrefix>$&nbsp;</span>
          <input matInput type="number" min="1" [(ngModel)]="budget" name="budget" />
          <mat-hint>Blank for no limit.</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="narrow">
          <mat-label>Services at once</mat-label>
          <input matInput type="number" min="1" [(ngModel)]="maxServices" name="maxServices" />
          <mat-hint>Blank for no limit.</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="narrow">
          <mat-label>Changes a month</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="maxSwitches" name="maxSwitches" />
          <mat-hint>0 keeps things as they are.</mat-hint>
        </mat-form-field>

        <mat-form-field appearance="outline" class="wide">
          <mat-label>What matters most</mat-label>
          <mat-select [(ngModel)]="coverageWeight" name="coverageWeight">
            @for (option of priorities; track option.value) {
              <mat-option [value]="option.value">{{ option.label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <button mat-flat-button type="submit" [disabled]="loading()">
          {{ result() ? 'Replot' : 'Plot my subscriptions' }}
        </button>
      </form>

      @if (loading()) {
        <div class="centre"><mat-spinner diameter="36" /></div>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else {
        <!-- The alias needs its own block rather than riding on the chain
             above: "as" binds only on a primary if, never on an else-if. -->
        @if (result(); as plan) {
        @if (plan.violations.length > 0) {
          <!-- The independent checker disagreed with the model. This is a defect
               in Plotted, not in the plan the user asked for, and hiding it
               would leave them acting on advice we know is unsound. -->
          <section class="violations" role="alert">
            <h2><mat-icon>error</mat-icon> This plan failed its own check</h2>
            <p>
              An independent reimplementation of the rules disagrees with the
              plan below. Do not act on it &mdash; this is a defect in Plotted.
            </p>
            <ul>
              @for (violation of plan.violations; track violation) {
                <li>{{ violation }}</li>
              }
            </ul>
          </section>
        }

        @if (plan.diagnosis; as diagnosis) {
          <!-- A dead end, and deliberately calm. An infeasible plan is the
               optimiser working correctly: the limits were the request and no
               plan satisfies them. The red banner above is for the other
               thing — Plotted producing a plan that fails its own check — and
               the two must never look alike. -->
          <section class="diagnosis" role="status">
            <plotted-icon [name]="plan.status === 'infeasible' ? 'dead-end' : 'waypoint'" [size]="30" />
            <h2>
              {{
                plan.status === 'infeasible'
                  ? 'No route fits.'
                  : 'Nothing to plan against'
              }}
            </h2>
            <p class="explanation">{{ diagnosis.explanation }}</p>
            @if (diagnosis.bindingConstraint) {
              <p class="hint">
                The limit that made it impossible:
                <strong>{{ constraintLabel(diagnosis.bindingConstraint) }}</strong
                >. Nothing was relaxed to produce an answer anyway.
              </p>
            }
            @if (plan.status === 'nothing_to_plan') {
              <p class="hint">
                <a routerLink="/watchlist">Add what you want to watch</a> and this
                will have something to work with.
              </p>
            }
          </section>
        } @else {
        @if (plan.objective; as objective) {
          <!-- The numbers first, then the map, then the sentence. Figures make
               comparison fast and prose makes interpretation fast, and the plan
               needs both: three stats alone are hard to act on, and a sentence
               alone hides the shape of the year. -->
          <p class="route-label coordinates">Your route</p>
          <section class="summary">
            <!-- Not "saved". A saving needs a counterfactual — what holding
                 everything would have cost — and the response carries no
                 per-service price to build one from, only the chosen plan's
                 total. Inventing the comparison would put a number in the most
                 quotable position on the screen that nothing computed. -->
            <div class="stat">
              <span class="value readout">{{ money(plan.totalCents ?? 0) }}</span>
              <span class="label">over {{ plan.horizonMonths }} months</span>
            </div>
            <div class="stat">
              <span class="value readout">{{ percent(objective.coverage) }}</span>
              <span class="label">of your list, priority-weighted</span>
            </div>
            <div class="stat">
              <span class="value readout">{{ switchCount() }}</span>
              <span class="label">
                {{ switchCount() === 1 ? 'change' : 'changes' }} to make
              </span>
            </div>
          </section>

          <plotted-plan-transit-map [months]="plan.months" [covered]="plan.covered" />

          <p class="verdict">
            This route holds
            <strong>{{ servicesHeld() }}</strong>
            {{ servicesHeld() === 1 ? 'service' : 'services' }} across
            {{ plan.horizonMonths }} months for
            <strong>{{ money(plan.totalCents ?? 0) }}</strong>, covering
            <strong>{{ percent(objective.coverage) }}</strong> of your priority-weighted list.
          </p>

          <!-- The month-by-month list stays, folded away. The map answers "what
               is the shape of this plan"; someone about to actually cancel
               something needs the exact names for a given month, and that is a
               different question rather than a redundant one. -->
          <details class="by-month">
            <summary>Month by month</summary>
            <ol class="months">
              @for (month of plan.months; track month.month) {
                <li>
                  <div class="when">
                    <span class="name">{{ monthLabel(month.month) }}</span>
                    <span class="cost readout">{{ money(month.monthlyCents) }}</span>
                  </div>
                  <div class="services">
                    @if (month.subscribed.length === 0) {
                      <span class="none">Nothing &mdash; pay for nothing this month</span>
                    }
                    @for (service of month.subscribed; track service.providerId) {
                      <span
                        class="chip"
                        [class.starting]="isStarting(month.month, service.providerId)"
                      >
                        {{ service.name }}
                      </span>
                    }
                    @for (service of month.stopped; track service.providerId) {
                      <span class="chip stopping">{{ service.name }}</span>
                    }
                  </div>
                </li>
              }
            </ol>
          </details>

          @if (plan.sensitivity.length > 0) {
            <section class="sensitivity">
              <h2>What one more would buy</h2>
              <!-- Each line is a second solve of the same model with one limit
                   lifted by a unit. A constraint that changes nothing is not
                   listed at all, because reporting it as binding is how a panel
                   like this turns into noise. -->
              <ul>
                @for (item of plan.sensitivity; track item.constraint) {
                  <li>
                    <strong>{{ item.relaxedBy }}</strong>
                    {{ sensitivitySentence(item) }}
                  </li>
                }
              </ul>
            </section>
          }

          @if (plan.uncovered.length > 0) {
            <section class="lists">
              <h2>What this plan does not get you</h2>
              <ul class="titles">
                @for (title of plan.uncovered; track title.titleId) {
                  <li>
                    <a [routerLink]="['/titles', title.titleId]">{{ title.name }}</a>
                    @if (title.reason === 'not_carried') {
                      <span class="why">nothing you can subscribe to has it</span>
                    } @else {
                      <span class="why">
                        on {{ title.availableOn.join(', ') }}, which the plan
                        could not fit
                      </span>
                    }
                  </li>
                }
              </ul>
            </section>
          }
        }
        }

        @if (excludedCount() > 0) {
          <section class="excluded">
            <h2>Left out of the calculation</h2>
            <p class="why-excluded">
              These are on your list but could not affect a subscription
              decision. They are shown rather than dropped, because a title
              missing for one of these reasons is a completely different fact
              from one no plan could afford.
            </p>

            @if (plan.excluded.freeToWatch.length > 0) {
              <h3>Already free to watch</h3>
              <ul class="titles">
                @for (title of plan.excluded.freeToWatch; track title.titleId) {
                  <li>
                    <a [routerLink]="['/titles', title.titleId]">{{ title.name }}</a>
                    <span class="why">on {{ title.providerNames.join(', ') }}</span>
                  </li>
                }
              </ul>
            }

            @if (plan.excluded.neverChecked.length > 0) {
              <h3>Never checked</h3>
              <p class="why-excluded">
                Plotted does not know where these are streaming yet, so counting
                them as uncovered would blame every service for a gap in our own
                data.
              </p>
              <ul class="titles">
                @for (title of plan.excluded.neverChecked; track title.titleId) {
                  <li>
                    <a [routerLink]="['/titles', title.titleId]">{{ title.name }}</a>
                  </li>
                }
              </ul>
            }

            @if (plan.excluded.unpricedService.length > 0) {
              <h3>On a service with no established price</h3>
              <p class="why-excluded">
                Plotted has no verified price for these services, and guessing
                one would put invented money into a calculation about your bill.
              </p>
              <ul class="titles">
                @for (title of plan.excluded.unpricedService; track title.titleId) {
                  <li>
                    <a [routerLink]="['/titles', title.titleId]">{{ title.name }}</a>
                    <span class="why">on {{ title.providerNames.join(', ') }}</span>
                  </li>
                }
              </ul>
            }
          </section>
        }

        @if (plan.solveMillis !== null) {
          <p class="provenance">
            Solved in {{ plan.solveMillis }} ms. Every figure above is
            recomputed from the chosen plan by an independent checker, not read
            back out of the solver.
          </p>
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
      max-width: 40rem;
    }

    .controls {
      display: flex;
      gap: 1rem 0.9rem;
      align-items: flex-start;
      flex-wrap: wrap;
      margin-bottom: 1.5rem;
    }

    /* Sized from their contents rather than to a fixed number.
       The labels here are whole phrases -- "Services at once", "Changes a
       month" -- and the hints are sentences, so a fixed 9.5rem clipped the
       labels and wrapped the hints onto two cramped lines. flex-basis sets the
       comfortable size, min-width stops them being squeezed below legibility
       when several share a row, and max-width keeps a number input from
       sprawling on a wide screen. */
    .narrow {
      flex: 1 1 13rem;
      min-width: 13rem;
      max-width: 15rem;
    }

    .wide {
      flex: 1 1 18rem;
      min-width: 18rem;
      max-width: 22rem;
    }

    /* Below this they stop sharing rows entirely, which reads better than
       several half-width boxes each too narrow for its own label. */
    @media (max-width: 32rem) {
      .narrow,
      .wide {
        flex-basis: 100%;
        max-width: none;
      }
    }

    .route-label {
      margin: 0 0 0.5rem;
    }

    .asked {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0.5rem 1rem;
      padding: 0.6rem 0.9rem;
      border: 1px solid var(--plotted-border);
      border-radius: var(--plotted-radius-sm);
      background: var(--plotted-surface);
      margin-bottom: 1.75rem;
      font-size: 0.68rem;
    }

    .link {
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      font-size: 0.72rem;
      color: var(--plotted-text-muted);
      text-decoration: underline;
      text-decoration-color: var(--plotted-border-strong);
      cursor: pointer;
      margin-left: auto;
    }

    .link:hover {
      color: var(--plotted-text);
      text-decoration-color: var(--plotted-accent);
    }

    .controls.hidden {
      display: none;
    }

    .summary {
      display: flex;
      gap: 2.5rem;
      flex-wrap: wrap;
      margin-bottom: 1.5rem;
    }

    .stat {
      display: grid;
      gap: 0.15rem;

      /* The figures are neutral, not orange. Orange is the route on the map
         below; these are measurements of it, and tinting them would spend the
         accent on three numbers that are not themselves a choice. */
      .value {
        font-size: 1.6rem;
        font-weight: 600;
        letter-spacing: -0.03em;
        color: var(--plotted-text);
      }

      .label {
        font-size: 0.75rem;
        color: var(--plotted-text-faint);
        max-width: 11rem;
      }
    }

    .verdict {
      font-size: 0.92rem;
      color: var(--plotted-text-muted);
      max-width: 42rem;
      margin: 0 0 1.5rem;

      strong {
        color: var(--plotted-text);
        font-weight: 600;
      }
    }

    .by-month {
      margin-bottom: 1.5rem;

      summary {
        cursor: pointer;
        font-size: 0.82rem;
        color: var(--plotted-text-muted);
        padding: 0.35rem 0;
      }

      summary:hover {
        color: var(--plotted-text);
      }

      .months {
        margin-top: 0.75rem;
      }
    }

    .months {
      list-style: none;
      margin: 0 0 1.5rem;
      padding: 0;
      display: grid;
      gap: 0.5rem;

      li {
        display: grid;
        grid-template-columns: 9rem 1fr;
        gap: 1rem;
        align-items: center;
        padding: 0.7rem 0.9rem;
        border: 1px solid var(--plotted-border);
        border-radius: 10px;
        background: var(--plotted-surface);
      }
    }

    .when {
      display: grid;
      gap: 0.1rem;

      .name {
        font-size: 0.85rem;
        font-weight: 600;
      }

      .cost {
        font-size: 0.75rem;
        color: var(--plotted-text-faint);
      }
    }

    .services {
      display: flex;
      gap: 0.4rem;
      flex-wrap: wrap;
    }

    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.2rem;
      padding: 0.22rem 0.6rem;
      border-radius: 999px;
      font-size: 0.78rem;
      background: var(--plotted-surface-raised);
      border: 1px solid transparent;

      mat-icon {
        font-size: 0.9rem;
        width: 0.9rem;
        height: 0.9rem;
      }
    }

    /* Starting and stopping are coloured differently because they are not the
       same event: one costs money you were not spending, the other costs access
       you had. */
    .chip.starting {
      border-color: var(--plotted-accent);
      color: var(--plotted-accent);
    }

    .chip.stopping {
      border-color: var(--plotted-danger);
      color: var(--plotted-danger);
      text-decoration: line-through;
    }

    .none {
      font-size: 0.78rem;
      color: var(--plotted-text-faint);
    }

    .sensitivity,
    .lists,
    .excluded {
      border-top: 1px solid var(--plotted-border);
      padding-top: 1.1rem;
      margin-bottom: 1.25rem;

      h2 {
        margin: 0 0 0.6rem;
        font-size: 0.95rem;
        font-weight: 600;
      }

      h3 {
        margin: 0.9rem 0 0.3rem;
        font-size: 0.8rem;
        font-weight: 600;
        color: var(--plotted-text-faint);
      }

      ul {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 0.3rem;
        font-size: 0.82rem;
      }
    }

    .titles li {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
      align-items: baseline;

      a {
        color: inherit;
      }
    }

    .why {
      font-size: 0.75rem;
      color: var(--plotted-text-faint);
    }

    .why-excluded {
      font-size: 0.78rem;
      color: var(--plotted-text-faint);
      margin: 0 0 0.5rem;
      max-width: 40rem;
    }

    .diagnosis {
      border: 1px dashed var(--plotted-border-strong);
      border-radius: 12px;
      padding: 2rem 1.5rem;
      text-align: center;
      margin-bottom: 1.25rem;
      display: grid;
      justify-items: center;

      plotted-icon {
        color: var(--plotted-text-faint);
      }

      h2 {
        margin: 0.6rem 0 0.6rem;
        font-size: 1.05rem;
        font-weight: 600;
      }
    }

    .explanation {
      margin: 0 auto 0.8rem;
      max-width: 34rem;
      font-size: 0.9rem;
    }

    /* Loud, and staying loud. This is Plotted disagreeing with itself, not a
       constraint that could not be met -- turning it into map language would
       dress a genuine defect up as product personality. Solid red border,
       filled tint, no cartography. */
    .violations {
      border: 1px solid var(--plotted-critical);
      background: rgb(229 72 77 / 8%);
      border-radius: 12px;
      padding: 1rem 1.25rem;
      margin-bottom: 1.25rem;

      h2 {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        margin: 0 0 0.4rem;
        font-size: 0.95rem;
        color: var(--plotted-danger);
      }

      p {
        margin: 0 0 0.5rem;
        font-size: 0.82rem;
      }

      ul {
        margin: 0;
        padding-left: 1.1rem;
        font-size: 0.8rem;
        color: var(--plotted-text-faint);
      }
    }

    .hint,
    .provenance {
      font-size: 0.78rem;
      color: var(--plotted-text-faint);
      margin: 0;
    }

    .provenance {
      border-top: 1px solid var(--plotted-border);
      padding-top: 0.9rem;
    }

    .centre {
      display: grid;
      place-items: center;
      padding: 3rem;
    }

    .error {
      color: var(--plotted-danger);
      font-size: 0.88rem;
    }
  `,
})
export class PlanPage {
  private readonly plans = inject(PlanService);

  protected readonly result = signal<PlanResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Whether the constraint form is open. Opens itself when there is no plan yet. */
  protected readonly editing = signal(false);

  protected readonly horizons: readonly number[] = [3, 6, 9, 12];

  /**
   * One dial rather than three weights that have to sum to 1. The interesting
   * axis is only ever "how much do I care about seeing my list versus paying for
   * it"; the API splits the remainder between cost and churn.
   */
  protected readonly priorities: readonly { value: number; label: string }[] = [
    { value: 0.8, label: 'Seeing my list' },
    { value: 0.55, label: 'A balance (default)' },
    { value: 0.3, label: 'Keeping the bill down' },
  ];

  protected horizonMonths = 6;
  protected budget: number | null = null;
  protected maxServices: number | null = null;
  protected maxSwitches: number | null = null;
  protected coverageWeight = 0.55;

  protected readonly switchCount = computed(() => {
    const plan = this.result();
    if (!plan) return 0;
    return plan.months.reduce((total, month) => total + month.started.length + month.stopped.length, 0);
  });

  protected readonly excludedCount = computed(() => {
    const plan = this.result();
    if (!plan) return 0;
    const excluded = plan.excluded;
    return (
      excluded.freeToWatch.length + excluded.neverChecked.length + excluded.unpricedService.length
    );
  });

  protected money(cents: number): string {
    return `$${(cents / 100).toFixed(2)}`;
  }

  protected percent(fraction: number): string {
    return `${Math.round(fraction * 100)}%`;
  }

  /**
   * Distinct services the plan holds at any point in the horizon.
   *
   * Distinct rather than summed across months: a service held for three months
   * is one subscription somebody manages, not three, and the sentence is about
   * how many things they end up paying for.
   */
  /**
   * What was asked, in one line, for when the form is folded away.
   *
   * Every constraint appears, including the ones left blank — "no budget" is
   * as much a part of how to read a plan as a figure would be, and omitting it
   * would let someone assume a limit was in force when it was not.
   */
  protected constraintSummary(): string {
    return [
      `${this.horizonMonths} months`,
      this.budget && this.budget > 0 ? `$${this.budget}/mo` : 'no budget',
      this.maxServices && this.maxServices > 0 ? `${this.maxServices} at once` : 'any number',
      this.maxSwitches !== null && this.maxSwitches >= 0
        ? `${this.maxSwitches} ${this.maxSwitches === 1 ? 'change' : 'changes'}/mo`
        : 'any changes',
      this.priorities.find((p) => p.value === this.coverageWeight)?.label ?? '',
    ]
      .filter(Boolean)
      .join('  ·  ');
  }

  protected servicesHeld(): number {
    const plan = this.result();
    if (!plan) return 0;
    return new Set(
      plan.months.flatMap((month) => month.subscribed.map((service) => service.providerId)),
    ).size;
  }

  protected constraintLabel(constraint: string): string {
    return CONSTRAINT_LABELS[constraint] ?? constraint;
  }

  protected monthLabel(month: number): string {
    if (month === 0) return 'This month';
    const date = new Date();
    date.setDate(1);
    date.setMonth(date.getMonth() + month);
    return date.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  }

  protected isStarting(month: number, providerId: string): boolean {
    const plan = this.result();
    if (!plan) return false;
    const entry = plan.months.find((candidate) => candidate.month === month);
    return entry?.started.some((service) => service.providerId === providerId) ?? false;
  }

  /**
   * The sentence is assembled from the two deltas rather than written per case,
   * so it cannot claim something the numbers do not say. A relaxation that buys
   * coverage for nothing is a real and interesting outcome, and so is one that
   * costs money and buys none.
   */
  protected sensitivitySentence(item: Sensitivity): string {
    const coverage =
      item.coverageDelta > 0
        ? `buys ${this.percent(item.coverageDelta)} more of your list`
        : 'buys no more of your list';
    if (item.monthlyCentsDelta > 0) {
      return `${coverage} for ${this.money(item.monthlyCentsDelta)} more a month.`;
    }
    if (item.monthlyCentsDelta < 0) {
      return `${coverage} and saves ${this.money(-item.monthlyCentsDelta)} a month.`;
    }
    return `${coverage} at no extra cost.`;
  }

  protected ask(): void {
    this.loading.set(true);
    this.error.set(null);
    this.plans
      .plan({
        horizonMonths: this.horizonMonths,
        // Dollars in the form, cents on the wire: the conversion happens once,
        // here, rather than being rounded somewhere in the middle.
        maximumMonthlyCents:
          this.budget !== null && this.budget > 0 ? Math.round(this.budget * 100) : null,
        maximumActiveServices:
          this.maxServices !== null && this.maxServices > 0 ? this.maxServices : null,
        // Zero is deliberately kept: "change nothing" is a real request, and
        // treating it as "no limit" would answer the opposite question.
        maximumMonthlySwitches:
          this.maxSwitches !== null && this.maxSwitches >= 0 ? this.maxSwitches : null,
        coverageWeight: this.coverageWeight,
      })
      .subscribe({
        next: (response) => {
          this.result.set(response);
          this.loading.set(false);
          // Fold the form away now there is something to look at.
          this.editing.set(false);
        },
        error: (failure: unknown) => {
          this.error.set(messageFrom(failure));
          this.loading.set(false);
        },
      });
  }
}

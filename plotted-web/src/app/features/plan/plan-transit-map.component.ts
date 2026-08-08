import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { MonthPlan, PlanCoveredTitle } from '../../core/plan/plan.models';

interface Cell {
  readonly held: boolean;
  readonly started: boolean;
  readonly stopped: boolean;
  /** Titles this service covers in this month, shown only where they explain a start. */
  readonly titles: readonly string[];
}

interface ServiceRow {
  readonly providerId: string;
  readonly name: string;
  readonly cells: readonly Cell[];
  readonly monthsHeld: number;
}

/**
 * The plan as a transit map: each service a route across the months.
 *
 * This is the one place in Plotted where a metaphor is also the right
 * information design rather than a decoration on top of one. The data genuinely
 * is a set of temporal intervals over a shared axis, which is what a transit
 * diagram is for — and the ordered list it replaces made you reconstruct, by
 * reading, the six things this shows at a glance: what stays, what starts, what
 * stops, when each change happens, how long each service is held, and which
 * titles justify holding it.
 *
 * ### Two layers, so orange keeps meaning one thing
 *
 * Every service gets a faint neutral track across the whole horizon — that is
 * the planning window, the set of possibilities. The orange overlay is only
 * where the optimiser chose to subscribe. So the accent still means exactly
 * what it means everywhere else: this is the route Plotted picked.
 *
 * ### The markers
 *
 *  * filled point — a start: money you were not spending before
 *  * hollow ring  — a month billed on a service already held
 *  * X            — a cancellation, the route terminating
 *
 * Start and stop are drawn differently because they *are* different, and the
 * model says so: `PlanSolver` splits them into separate variables precisely
 * because starting costs money you were not spending and stopping costs access
 * you had. Collapsing them into one "change" marker would draw a picture the
 * model deliberately refuses to compute.
 *
 * ### Titles
 *
 * Only at a start, and at most two. The whole covered set would bury the
 * routes, and the question a start raises is "why am I being told to subscribe
 * to this?" — which two titles answer and forty do not. The full breakdown
 * stays in the lists below the map.
 */
@Component({
  selector: 'plotted-plan-transit-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="map" [style.--months]="months().length">
      <figcaption class="sr-only">
        Subscription plan over {{ months().length }} months, one row per service.
      </figcaption>

      <div class="row header" aria-hidden="true">
        <span class="name"></span>
        <div class="track">
          @for (label of monthLabels(); track $index) {
            <span class="month coordinates">{{ label }}</span>
          }
        </div>
      </div>

      @for (service of rows(); track service.providerId) {
        <div class="row">
          <span class="name">
            {{ service.name }}
            <span class="held coordinates">
              {{ service.monthsHeld }}/{{ months().length }} mo
            </span>
          </span>

          <div class="track">
            @for (cell of service.cells; track $index) {
              <span class="cell" [class.held]="cell.held">
                @if (cell.started) {
                  <span class="marker start" aria-hidden="true"></span>
                } @else if (cell.stopped) {
                  <span class="marker stop" aria-hidden="true">
                    <svg viewBox="0 0 12 12" fill="none" stroke="currentColor"
                         stroke-width="1.7" stroke-linecap="round">
                      <path d="M2 2l8 8" />
                      <path d="M10 2l-8 8" />
                    </svg>
                  </span>
                } @else if (cell.held) {
                  <span class="marker billed" aria-hidden="true"></span>
                }

                @if (cell.titles.length > 0) {
                  <span class="titles coordinates">{{ cell.titles.join(' · ') }}</span>
                }
              </span>
            }
          </div>
        </div>
      }

      <!-- Spelled out rather than left to be inferred. The shapes are only
           worth using because they are consistent across the product, and a
           reader meeting them here first needs telling once. -->
      <div class="legend coordinates">
        <span><i class="k-start"></i> starts</span>
        <span><i class="k-billed"></i> billed</span>
        <span><i class="k-stop">&times;</i> cancelled</span>
        <span><i class="k-idle"></i> not subscribed</span>
      </div>
    </figure>
  `,
  styles: `
    .map {
      margin: 0 0 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
      /* The routes stop being readable long before the page does. Scrolls
         inside its own box rather than making the page scroll sideways. */
      overflow-x: auto;
    }

    .row {
      display: grid;
      grid-template-columns: 9rem minmax(22rem, 1fr);
      align-items: center;
      gap: 0.75rem;
      min-height: 2.6rem;
    }

    .header {
      min-height: 1.5rem;
    }

    .name {
      display: flex;
      flex-direction: column;
      font-size: 0.85rem;
      font-weight: 500;
      line-height: 1.25;
      text-align: right;
    }

    .held {
      font-size: 0.6rem;
      color: var(--plotted-text-faint);
    }

    .track {
      display: grid;
      grid-template-columns: repeat(var(--months), 1fr);
      align-items: center;
      height: 100%;
    }

    .month {
      font-size: 0.62rem;
      padding-left: 0.1rem;
    }

    /* The faint track is the planning horizon: every month the optimiser could
       have chosen this service. Drawn for the whole row so the orange reads as
       a selection out of possibilities rather than as the only thing there. */
    .cell {
      position: relative;
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: center;
      border-top: var(--plotted-route-width) solid var(--plotted-border);
      /* Nudged so the line sits mid-row rather than at the top of the box. */
      background: none;
    }

    .cell::before {
      content: '';
      position: absolute;
      inset: 0;
    }

    .cell.held {
      border-top-color: var(--plotted-accent);
    }

    .marker {
      position: absolute;
      left: 0;
      top: 0;
      width: 0.5rem;
      height: 0.5rem;
      margin: calc(var(--plotted-route-width) * -1 - 0.25rem) 0 0 -0.25rem;
      border-radius: 50%;
    }

    .start {
      background: var(--plotted-accent);
    }

    .billed {
      border: 1.5px solid var(--plotted-accent);
      background: var(--plotted-bg);
    }

    .stop {
      width: 0.7rem;
      height: 0.7rem;
      margin: calc(var(--plotted-route-width) * -1 - 0.35rem) 0 0 -0.35rem;
      border-radius: 0;
      color: var(--plotted-text-faint);
      display: grid;
      place-items: center;

      svg {
        width: 100%;
        height: 100%;
      }
    }

    .titles {
      position: absolute;
      top: 0.45rem;
      left: 0.35rem;
      right: 0.25rem;
      font-size: 0.58rem;
      color: var(--plotted-text-faint);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem 1.25rem;
      margin-top: 0.85rem;
      padding-top: 0.75rem;
      border-top: 1px solid var(--plotted-border);
      font-size: 0.62rem;

      span {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
      }

      i {
        width: 0.5rem;
        height: 0.5rem;
        border-radius: 50%;
        flex-shrink: 0;
      }
    }

    .k-start { background: var(--plotted-accent); }
    .k-billed { border: 1.5px solid var(--plotted-accent); }

    .k-stop {
      border-radius: 0;
      font-style: normal;
      color: var(--plotted-text-faint);
      line-height: 0.5rem;
    }

    .k-idle {
      border-radius: 0;
      height: 0;
      width: 0.9rem;
      border-top: var(--plotted-route-width) solid var(--plotted-border);
    }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class PlanTransitMapComponent {
  readonly months = input.required<readonly MonthPlan[]>();
  readonly covered = input<readonly PlanCoveredTitle[]>([]);

  protected readonly monthLabels = computed(() => {
    const now = new Date();
    return this.months().map((_, index) =>
      new Date(now.getFullYear(), now.getMonth() + index, 1)
        .toLocaleDateString([], { month: 'short' })
        .toUpperCase(),
    );
  });

  protected readonly rows = computed<ServiceRow[]>(() => {
    const months = this.months();

    // Every service the plan touches, including ones only ever stopped — a
    // cancellation is a decision the map has to show, and a service that
    // appears solely in `stopped` would otherwise have no row to be cancelled
    // on.
    const names = new Map<string, string>();
    months.forEach((month) => {
      [...month.subscribed, ...month.started, ...month.stopped].forEach((service) => {
        names.set(service.providerId, service.name);
      });
    });

    const titlesByKey = new Map<string, string[]>();
    this.covered().forEach((title) => {
      const key = `${title.providerId}:${title.month}`;
      const existing = titlesByKey.get(key);
      if (existing) existing.push(title.name);
      else titlesByKey.set(key, [title.name]);
    });

    return [...names.entries()]
      .map(([providerId, name]) => {
        const cells = months.map((month) => {
          const started = month.started.some((s) => s.providerId === providerId);
          const titles = started ? (titlesByKey.get(`${providerId}:${month.month}`) ?? []) : [];
          return {
            held: month.subscribed.some((s) => s.providerId === providerId),
            started,
            stopped: month.stopped.some((s) => s.providerId === providerId),
            titles: titles.slice(0, 2),
          };
        });

        return {
          providerId,
          name,
          cells,
          monthsHeld: cells.filter((cell) => cell.held).length,
        };
      })
      // Longest-held first: the services the plan is most committed to are the
      // ones the reader is looking for, and a stable order stops the rows
      // reshuffling between two solves of nearly the same problem.
      .sort((a, b) => b.monthsHeld - a.monthsHeld || a.name.localeCompare(b.name));
  });
}

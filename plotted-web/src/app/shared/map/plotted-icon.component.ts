import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The six icons Material cannot say.
 *
 * Deliberately *not* a replacement for the icon set. Close, delete, edit,
 * search and chevrons stay Material — they are generic actions, they are
 * already understood, and redrawing them would be sixty icons of work for no
 * gain. These six exist because each one names a Plotted concept that no
 * general-purpose icon library has a symbol for:
 *
 * | name          | means                                            |
 * |---------------|--------------------------------------------------|
 * | `destination` | the chosen thing — recommendation, final answer  |
 * | `waypoint`    | a possibility: a title, a watchlist entry        |
 * | `fork`        | a decision between routes                        |
 * | `dead-end`    | blocked, or a constraint that cannot be met      |
 * | `boundary`    | a date something changes — renewal, removal      |
 * | `convergence` | several people arriving at one choice            |
 *
 * One grid and one stroke system for all six, which is what makes them read
 * as a set rather than as six drawings: 24×24, 1.6 stroke, round caps and
 * joins, no fill unless the state genuinely is "filled in".
 *
 * Colour is inherited from `currentColor` throughout, so an icon is orange
 * only where the surrounding code has already decided this is the plotted
 * choice. The icon never asserts that on its own.
 */
@Component({
  selector: 'plotted-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      viewBox="0 0 24 24"
      [attr.width]="size()"
      [attr.height]="size()"
      fill="none"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
      [attr.role]="label() ? 'img' : 'presentation'"
      [attr.aria-label]="label() || null"
      [attr.aria-hidden]="label() ? null : 'true'"
    >
      @switch (name()) {
        @case ('destination') {
          <!-- The X, with the route that arrives at it. -->
          <path d="M2 19c5 0 5-7 10-7" opacity="0.55" />
          <path d="M14 8l7 7" />
          <path d="M21 8l-7 7" />
        }

        @case ('waypoint') {
          <!-- Hollow: somewhere you could go, not somewhere chosen. -->
          <circle cx="12" cy="12" r="5.5" />
        }

        @case ('fork') {
          <!-- One path in, two out. The decision itself. -->
          <path d="M3 12h5" />
          <path d="M8 12c4 0 4-6 8-6" />
          <path d="M8 12c4 0 4 6 8 6" />
          <circle cx="18.5" cy="6" r="2" />
          <circle cx="18.5" cy="18" r="2" />
        }

        @case ('dead-end') {
          <!-- A route that stops at a wall. Not an X: an X is an arrival, and
               this is the opposite of arriving. -->
          <path d="M3 12h11" />
          <path d="M18 5v14" />
        }

        @case ('boundary') {
          <!-- A route crossing a dated line. The vertical is the date. -->
          <path d="M3 16h18" opacity="0.55" />
          <path d="M14 3v18" stroke-dasharray="2.5 3" />
        }

        @case ('convergence') {
          <!-- Several routes arriving at the same place. -->
          <path d="M3 5c7 0 5 7 11 7" />
          <path d="M3 12h11" />
          <path d="M3 19c7 0 5-7 11-7" />
          <circle cx="17.5" cy="12" r="2.5" />
        }
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      line-height: 0;
      color: inherit;
    }
  `,
})
export class PlottedIconComponent {
  readonly name = input.required<PlottedIconName>();
  readonly size = input(20);

  /**
   * Accessible name. Empty by default, because most placements sit beside the
   * text they illustrate and announcing "waypoint" before every title is
   * noise. Set it when the icon is the only thing carrying the meaning.
   */
  readonly label = input('');

  /** Kept so a template can bind without a string literal typo going unnoticed. */
  protected readonly resolved = computed(() => this.name());
}

export type PlottedIconName =
  | 'destination'
  | 'waypoint'
  | 'fork'
  | 'dead-end'
  | 'boundary'
  | 'convergence';

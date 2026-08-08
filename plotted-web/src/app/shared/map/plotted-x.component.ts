import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The Plotted X — the destination marker, and the product's graphical signature.
 *
 * Not a generic close icon. The distinguishing move is that one arm of the X is
 * the *end of a route*: a line travels in from the left, bends, and terminates
 * in the cross. That is the whole product in one mark — a path through the mess
 * of streaming that stops at exactly one place.
 *
 * It is deliberately usable at two very different jobs:
 *
 *  * `variant="mark"` — the bare X, for the logo, the favicon, a selected title,
 *    a completed one, an empty state.
 *  * `variant="route"` — the X with its incoming route, for the moments where
 *    the *arriving* is the point: the final recommendation, the landing hero.
 *
 * Colour follows the one rule in `styles.scss`: the X is orange because it is
 * always the plotted choice. When it marks something that is *not* chosen —
 * a rejected route, a title that has left — pass `muted`, which drops it to the
 * neutral and is the visual difference between "here" and "not here".
 */
@Component({
  selector: 'plotted-x',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.viewBox]="variant() === 'route' ? '0 0 60 24' : '0 0 24 24'"
      [attr.height]="size()"
      [attr.width]="variant() === 'route' ? size() * 2.5 : size()"
      fill="none"
      role="img"
      [attr.aria-label]="label()"
      [attr.aria-hidden]="label() ? null : 'true'"
    >
      @if (variant() === 'route') {
        <!-- The approach. Grey rather than orange even here: the journey is
             possibility, and only the destination is the answer. -->
        <path
          class="tail"
          d="M 2 19 C 16 19, 20 12, 34 12 L 41 12"
          stroke-linecap="round"
        />
      }

      <g [attr.transform]="variant() === 'route' ? 'translate(36 0)' : ''">
        <path class="arm" d="M 7 7 L 17 17" stroke-linecap="round" />
        <path class="arm" d="M 17 7 L 7 17" stroke-linecap="round" />
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      line-height: 0;
    }

    .arm {
      stroke: var(--plotted-accent);
      stroke-width: 2.25;
    }

    .tail {
      stroke: var(--plotted-border-strong);
      stroke-width: var(--plotted-route-width);
    }

    /* A destination that is not the chosen one: a rejected fork, a title that
       has left a service. Same shape, so it still reads as a destination, and
       no accent, so it cannot be mistaken for the answer. */
    :host(.muted) .arm {
      stroke: var(--plotted-text-faint);
    }
  `,
})
export class PlottedXComponent {
  readonly variant = input<'mark' | 'route'>('mark');

  /** Height in pixels. Width follows, and doubles for the route variant. */
  readonly size = input(24);

  /**
   * Accessible name. Left empty by default because in almost every placement
   * the X sits beside the text it marks, and announcing "destination" before
   * every title name is noise rather than help.
   */
  readonly label = input('');
}

import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export interface HeroWaypoint {
  /** A provider name, or whatever the route is passing by. */
  readonly label: string;
  readonly x: number;
  readonly y: number;
}

/**
 * The landing hero: several routes wander toward possibilities, one finds its
 * way through and stops.
 *
 * This exists to make an argument that the copy underneath would need a
 * paragraph for — Netflix opens on a hundred destinations, Plotted opens on one
 * route. Grey lines are the services you already scroll through; the orange one
 * is the answer, and it terminates in an X rather than trailing off.
 *
 * ### Why it is drawn rather than animated with a library
 *
 * The whole thing is two `stroke-dashoffset` transitions on paths that are
 * already in the DOM, so there is no animation dependency, nothing to load, and
 * it degrades to a finished static diagram if the animation never runs. That
 * last property is what makes the reduced-motion path below honest rather than
 * a downgrade: someone who has asked for less motion sees the same completed
 * picture, immediately, rather than an empty box.
 */
@Component({
  selector: 'plotted-hero-route',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 320 190" fill="none" role="img" [attr.aria-label]="alt()">
      <!-- Contour texture. Barely visible on purpose: it should register as
           "this is a map" without ever competing with the route. -->
      <g class="contours" aria-hidden="true">
        <path d="M -20 40 C 60 10, 130 70, 210 40 S 320 20, 350 45" />
        <path d="M -20 78 C 70 48, 140 108, 220 78 S 320 58, 350 83" />
        <path d="M -20 150 C 60 122, 150 178, 230 148 S 320 128, 350 152" />
      </g>

      <!-- The routes not taken. They stop at hollow waypoints: a service you
           could have opened and would still be scrolling through. -->
      <g class="abandoned" aria-hidden="true">
        <path d="M 16 96 C 70 96, 78 44, 128 44" />
        <path d="M 16 96 C 66 96, 84 150, 132 150" />
        <path d="M 16 96 C 88 96, 104 118, 150 118" />
      </g>

      <g class="waypoints" aria-hidden="true">
        @for (waypoint of waypoints(); track waypoint.label) {
          <circle [attr.cx]="waypoint.x" [attr.cy]="waypoint.y" r="3.5" />
          <text [attr.x]="waypoint.x + 9" [attr.y]="waypoint.y + 3.5">
            {{ waypoint.label }}
          </text>
        }
      </g>

      <!-- Where you are. -->
      <circle class="origin" cx="16" cy="96" r="4" aria-hidden="true" />

      <!-- The plotted route. Drawn last so it sits above everything it passed. -->
      <path
        class="chosen"
        d="M 16 96 C 84 96, 96 82, 150 82 S 214 96, 250 96"
        stroke-linecap="round"
        aria-hidden="true"
      />

      <g class="destination" aria-hidden="true">
        <path d="M 250 89 L 264 103" stroke-linecap="round" />
        <path d="M 264 89 L 250 103" stroke-linecap="round" />
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: block;
    }

    svg {
      width: 100%;
      height: auto;
      overflow: visible;
    }

    .contours path {
      stroke: var(--plotted-text);
      stroke-width: 1;
      opacity: 0.04;
    }

    .abandoned path {
      stroke: var(--plotted-border-strong);
      stroke-width: var(--plotted-route-width);
      opacity: 0.75;
    }

    .waypoints circle {
      fill: none;
      stroke: var(--plotted-border-strong);
      stroke-width: 1.5;
    }

    .waypoints text {
      fill: var(--plotted-text-faint);
      font-family: var(--plotted-mono);
      font-size: 7.5px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .origin {
      fill: var(--plotted-accent);
    }

    .chosen {
      stroke: var(--plotted-accent);
      stroke-width: 2;
      /* Roughly the path length. It only has to be >= the true length for the
         dash to cover it before the animation starts. */
      stroke-dasharray: 300;
      animation: plotted-draw 1100ms ease-out 250ms both;
      --draw-length: 300;
    }

    .destination path {
      stroke: var(--plotted-accent);
      stroke-width: 2.25;
      transform-origin: 257px 96px;
      /* Lands after the route arrives, not with it. The X is the conclusion. */
      animation: plotted-land 260ms cubic-bezier(0.2, 0.8, 0.3, 1) 1250ms both;
    }

    /* The finished diagram makes the same argument as the animation, so a
       reduced-motion reader loses nothing but the drawing. */
    @media (prefers-reduced-motion: reduce) {
      .chosen,
      .destination path {
        animation: none;
        stroke-dashoffset: 0;
        opacity: 1;
        transform: none;
      }
    }
  `,
})
export class HeroRouteComponent {
  readonly waypoints = input<readonly HeroWaypoint[]>([
    { label: 'Netflix', x: 128, y: 44 },
    { label: 'Prime', x: 150, y: 118 },
    { label: 'Disney+', x: 132, y: 150 },
  ]);

  readonly alt = input(
    'A route passing several streaming services and ending at a single destination.',
  );
}

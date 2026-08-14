import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export interface PlottedContribution {
  readonly reason: string;
  /** How much of the score this accounted for, 0 to 1. */
  readonly share: number;
}

/**
 * A recommendation's feature contributions, drawn as plotted points on an axis.
 *
 * This is the third sense of "plot" doing real work: these are coordinates, and
 * the point's position on its track *is* the number beside it. A reader can
 * compare five features at a glance by where the dots sit, which a list of
 * percentages does not allow.
 *
 * ### What must not change
 *
 * Every value here is a real contribution the ranker computed — the same
 * numbers it ranked on, not a description of them. The rule holds across the
 * whole project: a reason is a measured feature contribution or it is not
 * shown. Nothing on this component may ever be generated, rounded into a
 * flattering shape, or padded out to fill the axis, because the moment it is,
 * the interface is confidently explaining a decision it did not make.
 *
 * The shares are also left *unnormalised*. They are fractions of the whole
 * score and generally do not reach 1, so the longest track on screen is usually
 * well short of full — which is honest. Rescaling so the top feature hits 100%
 * would make every pick look equally well-explained.
 */
@Component({
  selector: 'plotted-contribution-plot',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (caption()) {
      <p class="caption coordinates">{{ caption() }}</p>
    }

    <ul class="plot">
      @for (contribution of contributions(); track contribution.reason) {
        <li>
          <span class="label">{{ contribution.reason }}</span>

          <span class="track" aria-hidden="true">
            <span class="run" [style.width.%]="percent(contribution)"></span>
            <span class="point" [style.left.%]="percent(contribution)"></span>
          </span>

          <span class="value readout">{{ rounded(contribution) }}</span>
        </li>
      }
    </ul>
  `,
  styles: `
    .caption {
      margin: 0 0 0.5rem;
    }

    .plot {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 0.4rem;
    }

    li {
      display: grid;
      /* Three fixed columns rather than flex: the tracks have to start at the
         same x or the dots are not comparable, which is the only reason to draw
         this instead of a list. */
      grid-template-columns: 6.5rem 1fr 2.25rem;
      align-items: center;
      gap: 0.6rem;
      font-size: 0.76rem;
      color: var(--plotted-text-muted);
    }

    .label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .track {
      position: relative;
      height: 1px;
      background: var(--plotted-border);
      display: block;
    }

    /* The travelled part of the axis. */
    .run {
      position: absolute;
      inset: 0 auto 0 0;
      height: 1px;
      background: var(--plotted-accent);
      opacity: 0.55;
    }

    .point {
      position: absolute;
      top: 50%;
      width: 0.4rem;
      height: 0.4rem;
      margin: -0.2rem 0 0 -0.2rem;
      border-radius: 50%;
      background: var(--plotted-accent);
    }

    .value {
      font-size: 0.72rem;
      color: var(--plotted-text-faint);
      text-align: right;
    }
  `,
})
export class ContributionPlotComponent {
  readonly contributions = input.required<readonly PlottedContribution[]>();
  readonly caption = input('');

  protected percent(contribution: PlottedContribution): number {
    // Clamped because a share is a fraction of the score and a point that
    // escaped its track would be a rendering bug reading as a great result.
    return Math.max(0, Math.min(100, contribution.share * 100));
  }

  protected rounded(contribution: PlottedContribution): number {
    return Math.round(this.percent(contribution));
  }
}

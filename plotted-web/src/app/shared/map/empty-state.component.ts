import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { PlottedIconComponent, PlottedIconName } from './plotted-icon.component';

/**
 * The one empty state, in four moods.
 *
 * Eight screens had their own — a Material icon, a heading, a line of text —
 * and the drift between them was visible design debt. More importantly the
 * *distinctions* were being lost: "you have not added anything yet" and "your
 * constraints exclude everything" are completely different facts, and both
 * were rendering as a grey icon and an apology.
 *
 * The four modes exist because those four facts exist:
 *
 * | mode        | the fact                                        |
 * |-------------|-------------------------------------------------|
 * | `blank`     | nothing here yet — you have not started         |
 * | `dead-end`  | constraints excluded everything; a real answer  |
 * | `unknown`   | Plotted does not know, rather than there being nothing |
 * | `arrived`   | done, and there is nothing left to do           |
 *
 * `dead-end` and `unknown` are the two that matter. A dead end is a valid
 * optimiser result and must never look like a system failure — a genuine
 * failure gets a red banner instead, and the difference has to survive a
 * glance. `unknown` keeps Plotted from blaming a service for a gap in its own
 * data, which is the same rule the coverage denominator follows.
 *
 * Copy stays with the caller. A shared component that also owned the words
 * would flatten eight carefully-written messages into one generic one.
 */
@Component({
  selector: 'plotted-empty-state',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PlottedIconComponent],
  template: `
    <div class="empty" [class.dead]="mode() === 'dead-end'" role="status">
      @if (mode() === 'unknown') {
        <span class="glyph question" aria-hidden="true">?</span>
      } @else {
        <plotted-icon [name]="icon()" [size]="30" />
      }

      @if (eyebrow()) {
        <p class="coordinates">{{ eyebrow() }}</p>
      }

      <h2>{{ heading() }}</h2>

      <ng-content />
    </div>
  `,
  styles: `
    .empty {
      display: grid;
      justify-items: center;
      gap: 0.4rem;
      text-align: center;
      padding: clamp(2rem, 6vh, 3.5rem) 1.5rem;
      border: 1px solid var(--plotted-border);
      border-radius: var(--plotted-radius);
      color: var(--plotted-text-faint);
    }

    /* Dashed, because the boundary itself is the point: something stopped
       here. Still a border rather than an alarm -- this is an answer. */
    .empty.dead {
      border-style: dashed;
      border-color: var(--plotted-border-strong);
    }

    plotted-icon {
      color: var(--plotted-text-faint);
      margin-bottom: 0.35rem;
    }

    .glyph {
      font-family: var(--plotted-mono);
      font-size: 1.6rem;
      line-height: 1;
      color: var(--plotted-text-faint);
      margin-bottom: 0.5rem;
    }

    h2 {
      margin: 0;
      font-size: 1.1rem;
      font-weight: 600;
      color: var(--plotted-text);
    }

    /* The caller's copy and actions. Kept narrow so the sentence stays
       readable rather than stretching the width of the page. */
    ::ng-deep .empty > p {
      margin: 0;
      max-width: 32rem;
      font-size: 0.9rem;
      color: var(--plotted-text-muted);
    }

    ::ng-deep .empty > .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.6rem;
      justify-content: center;
      margin-top: 0.9rem;
    }
  `,
})
export class EmptyStateComponent {
  readonly mode = input<'blank' | 'dead-end' | 'unknown' | 'arrived'>('blank');
  readonly heading = input.required<string>();
  readonly eyebrow = input('');

  protected icon(): PlottedIconName {
    switch (this.mode()) {
      case 'dead-end':
        return 'dead-end';
      case 'arrived':
        return 'destination';
      default:
        return 'waypoint';
    }
  }
}

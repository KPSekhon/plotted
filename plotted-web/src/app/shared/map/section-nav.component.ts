import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/**
 * The subscriptions area, as three views of one question.
 *
 * Coverage and Cancel Culture used to be top-level navigation, which made the
 * product look larger and less deliberate than it is, and left "Plan" sitting
 * in the header meaning nothing to anybody who had not already learned the
 * branded name. Grouped here the sequence reads by itself:
 *
 *   Current   — what you pay for now
 *   Coverage  — what each service actually carries for you
 *   Cancel Culture — given all that, what you should pay for
 *
 * They are adjacent questions rather than the same one, which is why this is a
 * sub-navigation and not a merge. Coverage describes the landscape; Cancel
 * Culture plots a route through it, and only Cancel Culture is allowed to turn
 * anything orange.
 */
@Component({
  selector: 'plotted-section-nav',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="section-nav" aria-label="Subscriptions">
      <a routerLink="/subscriptions" routerLinkActive="is-active">Current</a>
      <a routerLink="/coverage" routerLinkActive="is-active">Coverage</a>
      <a routerLink="/plan" routerLinkActive="is-active">Cancel Culture</a>
    </nav>
  `,
  styles: `
    .section-nav {
      display: flex;
      gap: 0.25rem;
      flex-wrap: wrap;
      border-bottom: 1px solid var(--plotted-border);
      margin-bottom: 1.75rem;
    }

    a {
      padding: 0.5rem 0.85rem;
      font-size: 0.85rem;
      color: var(--plotted-text-muted);
      text-decoration: none;
      border-bottom: 2px solid transparent;
      margin-bottom: -1px;
      transition: color 0.12s ease;
    }

    a:hover {
      color: var(--plotted-text);
    }

    /* The current view is underlined rather than filled. This is a position
       indicator, not a recommendation, so it stays out of the accent's
       vocabulary -- orange here would read as "Plotted chose this tab". */
    a.is-active {
      color: var(--plotted-text);
      border-bottom-color: var(--plotted-border-strong);
    }
  `,
})
export class SectionNavComponent {}

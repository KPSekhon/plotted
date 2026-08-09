import { Component, inject } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';

/**
 * Says, on the screens where it matters, that the figures above were generated
 * rather than lived.
 *
 * ### Why this exists at all
 *
 * A demo is not dishonest for containing fixtures. Nobody expects a sample
 * account to have been used for six weeks. It becomes dishonest at the moment
 * the interface presents manufactured numbers in the same voice it would use
 * for measured ones — and Plotted's whole argument is that it will not state
 * what it cannot support. A taste profile fitted from seeded answers, and a
 * completion rate computed over invented viewing history, are exactly that
 * failure wearing the product's own clothes.
 *
 * ### Why it renders nothing for a real account
 *
 * The condition is `user.isDemo`, which comes from the server on every session.
 * A real account never sees this, so the note cannot become the sort of blanket
 * disclaimer people learn to skip; and because the flag travels with the
 * session rather than being remembered client-side, it cannot go on claiming
 * "sample" after a reload into a real account, or stop claiming it in a demo.
 *
 * ### Why it is quiet
 *
 * One line, at the size of a caption, under the thing it qualifies. A warning
 * banner across the top would read as an apology for the demo, and would
 * compete with the numbers it is annotating — which are the point of the
 * screen. Disclosure has to be adjacent to the claim to be read as being about
 * it, and small enough not to become the claim itself.
 *
 * Usage:
 *     <plotted-demo-note>Computed from generated viewing history.</plotted-demo-note>
 */
@Component({
  selector: 'plotted-demo-note',
  standalone: true,
  template: `
    @if (auth.user()?.isDemo) {
      <p class="demo-note">
        <span class="demo-note__tag">Sample data</span>
        <span class="demo-note__body"><ng-content /></span>
      </p>
    }
  `,
  styles: [
    `
      .demo-note {
        display: flex;
        align-items: baseline;
        gap: 0.5rem;
        flex-wrap: wrap;
        margin: 0.75rem 0 0;
        font-size: 0.8125rem;
        line-height: 1.5;
        color: var(--plotted-text-muted);
      }

      /* Deliberately not the accent colour. Orange means the plotted choice
         everywhere else in this interface, and spending it on a disclaimer
         would both dilute that and make the note louder than the figure it
         qualifies. */
      .demo-note__tag {
        flex: none;
        padding: 0.0625rem 0.375rem;
        border: 1px solid var(--plotted-border);
        border-radius: 2px;
        font-family: var(--plotted-mono);
        font-size: 0.6875rem;
        letter-spacing: 0.06em;
        text-transform: uppercase;
        color: var(--plotted-text-muted);
      }

      .demo-note__body {
        min-width: 0;
      }
    `,
  ],
})
export class DemoNoteComponent {
  protected readonly auth = inject(AuthService);
}

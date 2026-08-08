import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  ACCESS_TYPE_LABELS,
  ACCESS_TYPE_ORDER,
  AccessType,
  Availability,
  AvailabilityOffer,
} from '../../core/catalogue/catalogue.models';

/**
 * Where a title can be watched — with the honesty features rendered, not
 * buried.
 *
 * Three product rules from the spec become UI here:
 *
 *  * Every claim shows its source and when it was last verified.
 *  * Stale data is labelled and its prices are suppressed, because showing
 *    stale money is the documented failure mode, not showing stale presence.
 *  * Reduced confidence is visible, because "probably" and "definitely" are
 *    different answers to someone deciding what to watch.
 */
@Component({
  selector: 'plotted-availability-panel',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, MatChipsModule, MatIconModule, MatTooltipModule],
  template: `
    <section aria-label="Where to watch">
      <header class="panel-header">
        <h2>Where to watch</h2>
        @if (availability().stale) {
          <span
            class="stale-badge"
            matTooltip="This has not been re-checked recently. It may be out of date."
          >
            <mat-icon inline>history</mat-icon>
            @if (availability().lastVerifiedAt; as verified) {
              verified {{ verified | date: 'MMM d' }}
            } @else {
              never verified
            }
          </span>
        }
      </header>

      @if (availability().offers.length === 0) {
        <p class="none">
          @if (availability().stale && !availability().lastVerifiedAt) {
            Availability has not been checked yet. It will be picked up by the next nightly
            refresh.
          } @else {
            Nothing carries this in {{ availability().regionCode }} right now.
          }
        </p>
      } @else {
        @for (group of grouped(); track group.accessType) {
          <div class="group">
            <h3>{{ label(group.accessType) }}</h3>
            <ul class="offers">
              @for (offer of group.offers; track offer.providerSlug + offer.accessType) {
                <!-- The line renders the confidence column and the stale flag,
                     real columns rather than a mood: solid means the provider
                     feed was matched completely, dashed means part of it was
                     not, dotted means nobody has re-checked this recently.

                     Always paired with the word. Certainty must not depend on
                     noticing the difference between a dash and a dot, and a
                     stroke style is invisible to a screen reader. -->
                <li class="offer" [class]="'state-' + state(offer)">
                  <span class="route-line" aria-hidden="true"></span>
                  <span class="plot-point" aria-hidden="true"></span>

                  <span class="provider">{{ offer.providerName }}</span>

                  <span class="state coordinates">{{ stateLabel(offer) }}</span>

                  @if (offer.price !== null && !availability().stale) {
                    <span class="price readout">
                      {{ offer.price | currency: offer.currency ?? 'CAD' }}
                    </span>
                  } @else if (offer.price !== null && availability().stale) {
                    <!-- Stale money is worse than no money: the presence claim
                         survives, the price does not. -->
                    <span class="price suppressed">price hidden until re-verified</span>
                  }

                  <span class="provenance" matTooltip="Source: {{ offer.source }}">
                    checked {{ offer.verifiedAt | date: 'MMM d, h:mm a' }}
                  </span>

                  @if (state(offer) === 'probable') {
                    <span class="note">Some provider data could not be matched.</span>
                  }
                </li>
              }
            </ul>
          </div>
        }
      }

      <p class="attribution">{{ availability().attribution }}</p>
    </section>
  `,
  styles: `
    .panel-header {
      display: flex;
      align-items: baseline;
      gap: 0.75rem;

      h2 {
        font-size: 1.15rem;
        margin: 0;
      }
    }

    /* Was orange, which claimed this was the plotted choice. Staleness is a
       data-quality statement, so it takes the status palette instead. */
    .stale-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      font-size: 0.75rem;
      padding: 0.15rem 0.6rem;
      border-radius: 1rem;
      border: 1px solid var(--plotted-border-strong);
      color: var(--plotted-text-faint);
    }

    .none {
      opacity: 0.75;
    }

    .group {
      margin-top: 0.75rem;

      h3 {
        font-size: 0.85rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        opacity: 0.65;
        margin: 0 0 0.35rem;
      }
    }

    .offers {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }

    .offer {
      display: grid;
      grid-template-columns: 2.5rem auto minmax(0, 1fr);
      align-items: center;
      gap: 0.15rem 0.6rem;
      padding: 0.35rem 0;
    }

    /* The line runs into the point, so the offer reads as a route arriving at
       a provider rather than as a bullet with decoration beside it. */
    .route-line {
      grid-column: 1;
      height: 0;
      border-top: var(--plotted-route-width) solid var(--plotted-border-strong);
    }

    .offer .plot-point {
      grid-column: 2;
      margin-left: -0.75rem;
      background: var(--plotted-text-faint);
    }

    .provider {
      grid-column: 3;
      font-weight: 500;
    }

    .state {
      grid-column: 3;
      font-size: 0.62rem;
    }

    .price,
    .provenance,
    .note {
      grid-column: 3;
    }

    .price {
      font-size: 0.85rem;
    }

    .price.suppressed {
      font-style: italic;
      opacity: 0.6;
      font-size: 0.78rem;
    }

    .note {
      font-size: 0.72rem;
      color: var(--plotted-text-faint);
      max-width: 28rem;
    }

    /* Confirmed is the only state that gets a solid line. It is still neutral
       grey -- being certain a title is on Crave is not the same as Plotted
       recommending Crave, and only a recommendation earns the accent. */
    .state-confirmed .state {
      color: var(--plotted-text-muted);
    }

    .state-probable .route-line {
      border-top-style: dashed;
    }

    .state-probable .state {
      color: var(--plotted-warning);
    }

    .state-stale .route-line {
      border-top-style: dotted;
      opacity: 0.7;
    }

    .state-stale .state {
      color: var(--plotted-text-faint);
    }

    .state-stale .provider {
      color: var(--plotted-text-muted);
    }

    .provenance {
      font-size: 0.72rem;
      opacity: 0.55;
      cursor: help;
    }

    .attribution {
      font-size: 0.72rem;
      opacity: 0.6;
      margin-top: 1.25rem;
    }
  `,
})
export class AvailabilityPanelComponent {
  readonly availability = input.required<Availability>();

  /** Offers grouped by access type, cheapest way of watching first. */
  protected readonly grouped = computed(() => {
    const offers = this.availability().offers;
    return ACCESS_TYPE_ORDER.map((accessType) => ({
      accessType,
      offers: offers.filter((offer) => offer.accessType === accessType),
    })).filter((group) => group.offers.length > 0);
  });

  protected label(accessType: AccessType): string {
    return ACCESS_TYPE_LABELS[accessType];
  }

  /**
   * How far this offer can be trusted, as one of three states.
   *
   * Staleness is a property of the whole panel rather than of one offer — it
   * means nobody has re-checked this title recently — so it outranks
   * confidence. An offer recorded at full confidence three weeks ago is still
   * three weeks old, and saying "confirmed" about it would be the panel
   * vouching for something it has not looked at.
   */
  protected state(offer: AvailabilityOffer): 'confirmed' | 'probable' | 'stale' {
    if (this.availability().stale) return 'stale';
    return offer.confidence < 1 ? 'probable' : 'confirmed';
  }

  protected stateLabel(offer: AvailabilityOffer): string {
    switch (this.state(offer)) {
      case 'stale':
        return 'Stale';
      case 'probable':
        return 'Probable';
      default:
        return 'Confirmed';
    }
  }

  protected trackOffer(offer: AvailabilityOffer): string {
    return `${offer.providerSlug}:${offer.accessType}`;
  }
}

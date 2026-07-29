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
                <li class="offer">
                  <span class="provider">{{ offer.providerName }}</span>

                  @if (offer.price !== null && !availability().stale) {
                    <span class="price">
                      {{ offer.price | currency: offer.currency ?? 'CAD' }}
                    </span>
                  } @else if (offer.price !== null && availability().stale) {
                    <!-- Stale money is worse than no money: the presence claim
                         survives, the price does not. -->
                    <span
                      class="price suppressed"
                      matTooltip="Price hidden because this has not been verified recently."
                    >
                      price unverified
                    </span>
                  }

                  @if (offer.confidence < 1) {
                    <mat-icon
                      inline
                      class="low-confidence"
                      matTooltip="Recorded while part of the provider data could not be matched. Treat as probable, not certain."
                    >
                      help_outline
                    </mat-icon>
                  }

                  <span class="provenance" matTooltip="Source: {{ offer.source }}">
                    verified {{ offer.verifiedAt | date: 'MMM d, h:mm a' }}
                  </span>
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

    .stale-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      font-size: 0.75rem;
      padding: 0.15rem 0.6rem;
      border-radius: 1rem;
      background: var(--plotted-accent-soft);
      color: var(--plotted-accent);
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
      display: flex;
      align-items: baseline;
      gap: 0.6rem;
      flex-wrap: wrap;
    }

    .provider {
      font-weight: 500;
    }

    .price.suppressed {
      font-style: italic;
      opacity: 0.6;
    }

    .low-confidence {
      font-size: 1rem;
      opacity: 0.55;
      cursor: help;
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

  protected trackOffer(offer: AvailabilityOffer): string {
    return `${offer.providerSlug}:${offer.accessType}`;
  }
}

import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { PlottedIconComponent } from '../../shared/map/plotted-icon.component';

/**
 * What Plotted is, how it decides, and what it refuses to guess.
 *
 * This lives away from the home screen on purpose. Build status is interesting
 * to someone evaluating the project and irrelevant to someone who just wants to
 * watch something — and the home screen belongs to the second person.
 *
 * Written as a case study rather than a dashboard, because it is the one page
 * somebody reads end to end, and the interesting things about Plotted are its
 * *decisions*: why explanations are real contributions, why stale prices
 * vanish, why an optimiser refuses rather than relaxes. None of that is visible
 * from using the product for ninety seconds.
 *
 * ### Every claim here is checked against what is actually built
 *
 * The previous version said Plot Armour "estimates the risk that one is about
 * to [leave]". It does not: the nightly diff detects departures that have
 * already happened, and the removal-risk model is phase 12 and cannot start
 * until months of snapshot history exist. That sentence was the exact failure
 * this project refuses everywhere else — a confident claim with nothing behind
 * it — sitting on the page most likely to be read by someone deciding whether
 * to believe the rest.
 */
@Component({
  selector: 'plotted-about',
  standalone: true,
  imports: [RouterLink, PlottedIconComponent],
  template: `
    <article class="about">
      <header class="intro">
        <p class="coordinates">About</p>
        <h1>Plotted works for you, not for a platform.</h1>
        <p class="lede">
          Every streaming app recommends from its own catalogue. Netflix will not tell you the
          best thing tonight is on Crave, and Prime will not suggest cancelling Prime.
          Platform-neutrality is the only thing about this that is hard to copy.
        </p>
      </header>

      <section>
        <h2 class="coordinates">The problem</h2>
        <p class="statement">Streaming solved access and made choosing worse.</p>
        <p>
          Six services, four hundred things you could watch, and forty minutes of scrolling
          before settling for something you have already seen. The catalogues got bigger and
          the decision got harder, because nothing in the market is built to help you decide —
          each app is built to keep you inside it.
        </p>
      </section>

      <section>
        <h2 class="coordinates">What it does</h2>
        <dl class="features">
          <div>
            <dt><plotted-icon name="destination" [size]="16" /> Queue Theory</dt>
            <dd>
              One recommendation and two alternates, chosen from your list against the time you
              actually have and the services you already pay for. Every reason shown is a real
              feature contribution from the ranking.
            </dd>
          </div>
          <div>
            <dt><plotted-icon name="fork" [size]="16" /> Cancel Culture</dt>
            <dd>
              A constraint solver planning which subscriptions to hold month by month, with the
              price of each limit — what one more service would buy, and which constraint made a
              plan impossible.
            </dd>
          </div>
          <div>
            <dt><plotted-icon name="dead-end" [size]="16" /> Plot Armour</dt>
            <dd>
              Notices when a title on your list has left a service, and suppresses the alerts
              that are not worth sending — a title leaving a service you never subscribed to is
              not news.
            </dd>
          </div>
          <div>
            <dt><plotted-icon name="waypoint" [size]="16" /> Pilot Season</dt>
            <dd>
              Fifteen comparisons fitted into a taste profile that reports which of its own axes
              are worth trusting, and which were never asked about.
            </dd>
          </div>
        </dl>
      </section>

      <section>
        <h2 class="coordinates">How it decides</h2>
        <ol class="pipeline">
          <li><span class="step coordinates">Context</span> time, access policy, region</li>
          <li><span class="step coordinates">Filter</span> hard constraints, never softened</li>
          <li><span class="step coordinates">Rank</span> weighted features, renormalised</li>
          <li><span class="step coordinates">Diversify</span> alternates that differ in kind</li>
          <li><span class="step coordinates">Explain</span> the contributions it actually used</li>
        </ol>
        <p>
          The filters are hard on purpose. A recommender that quietly relaxes your constraints
          to avoid an empty answer has stopped answering the question you asked — so when
          nothing fits, Plotted says so and reports which constraint removed what.
        </p>
      </section>

      <!-- The section worth the whole page. Anyone can list features; the
           refusals are what a reader cannot get from a competitor's about
           page, and they are the reason to trust the numbers elsewhere. -->
      <section class="refusals">
        <h2 class="coordinates">What it refuses to guess</h2>
        <dl>
          <div>
            <dt>Availability</dt>
            <dd>
              Every claim carries its source and when it was last checked. Where the provider
              feed could only be partly matched it says <em>probable</em>, not
              <em>confirmed</em>.
            </dd>
          </div>
          <div>
            <dt>Prices</dt>
            <dd>
              Stale prices are hidden while the presence claim survives, and no price is ever
              invented. A guessed number would flow into the subscription optimiser and come
              back out as confident, wrong financial advice.
            </dd>
          </div>
          <div>
            <dt>Your taste</dt>
            <dd>
              A profile with nothing it can defend saying returns nothing rather than a
              plausible-looking number computed from noise — and an axis nobody asked about is
              reported differently from one where you were genuinely balanced.
            </dd>
          </div>
          <div>
            <dt>How far through you are</dt>
            <dd>
              Plotted does not track which episode you are on, so it never draws a position on
              one.
            </dd>
          </div>
        </dl>
      </section>

      <section>
        <h2 class="coordinates">How it is built</h2>
        <p class="stack coordinates">
          Angular &middot; Kotlin &middot; Spring Boot &middot; PostgreSQL &middot; jOOQ
          &middot; Redis &middot; OR-Tools CP-SAT &middot; ONNX Runtime
        </p>
        <p>
          A modular monolith with feature boundaries enforced by architecture tests. Temporal
          correctness lives in the schema: availability windows are date ranges with exclusion
          constraints, so duplicate rows are unrepresentable rather than merely discouraged —
          which is what keeps every coverage number the optimiser depends on honest.
        </p>
      </section>

      <section>
        <h2 class="coordinates">How far along</h2>
        <ul class="status">
          <li class="done">Accounts, sessions, and the Canadian schema</li>
          <li class="done">Catalogue, availability with provenance, nightly snapshots</li>
          <li class="done">Watchlists, subscriptions, coverage</li>
          <li class="done">Queue Theory &mdash; tonight&rsquo;s pick</li>
          <li class="done">Cancel Culture &mdash; the subscription optimiser</li>
          <li class="done">Evaluation harness, Pilot Season, outbox and Plot Armour</li>
          <li class="partial">
            End Credits &mdash; built, but both metrics correctly return nothing until somebody
            has used the product
          </li>
          <li class="partial">
            Learned ranking &mdash; the pipeline is proven end to end, and the model is
            deliberately not served: a boosted tree cannot produce the feature contributions the
            explanations are made of
          </li>
          <li>Deployment, and the scheduled jobs that need somewhere always running</li>
        </ul>
        <p class="faint note">
          Roughly five hundred titles are seeded, drawn from a live enumeration of what is
          actually streaming in Canada. The answers are real; the range they are chosen from is
          narrower than the market.
        </p>
      </section>

      <section class="data">
        <h2 class="coordinates">Where the data comes from</h2>
        <p>
          Title metadata and Canadian streaming availability come from TMDB and JustWatch. That
          data is imperfect, which is why every claim shows its provenance rather than being
          presented as fact.
        </p>
        <p><a routerLink="/legal/data-sources">Sources, terms and refresh cadence &rarr;</a></p>
      </section>
    </article>
  `,
  styles: `
    /* Reading width, not dashboard width. This is the one page somebody reads
       top to bottom, so it is set as prose. */
    .about {
      max-width: 44rem;
      display: flex;
      flex-direction: column;
      gap: 2.75rem;
      padding-bottom: 3rem;
    }

    .intro h1 {
      margin: 0.5rem 0 0.9rem;
      font-size: clamp(1.7rem, 1.3rem + 2vw, 2.4rem);
    }

    .lede {
      font-size: 1.05rem;
      color: var(--plotted-text-muted);
      margin: 0;
    }

    h2 {
      margin: 0 0 0.75rem;
      font-size: 0.68rem;
      font-weight: 500;
      padding-bottom: 0.5rem;
      border-bottom: 1px solid var(--plotted-border);
    }

    p {
      color: var(--plotted-text-muted);
      margin: 0 0 0.75rem;
    }

    p:last-child {
      margin-bottom: 0;
    }

    /* One sentence carrying a section. Set large because it is the argument,
       not an introduction to the argument. */
    .statement {
      font-size: 1.15rem;
      color: var(--plotted-text);
      font-weight: 500;
      letter-spacing: -0.01em;
      margin-bottom: 0.9rem;
    }

    .features,
    .refusals dl {
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 1.1rem;
    }

    dt {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-weight: 600;
      color: var(--plotted-text);
      margin-bottom: 0.2rem;
    }

    dt plotted-icon {
      color: var(--plotted-text-faint);
    }

    dd {
      margin: 0;
      color: var(--plotted-text-muted);
      font-size: 0.92rem;
    }

    .pipeline {
      list-style: none;
      margin: 0 0 1rem;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      /* A route down the left: the stages are genuinely sequential, so the
         line encodes something true rather than decorating a list. */
      border-left: 1px solid var(--plotted-border-strong);
      padding-left: 1.1rem;
    }

    .pipeline li {
      position: relative;
      font-size: 0.92rem;
      color: var(--plotted-text-muted);
    }

    .pipeline li::before {
      content: '';
      position: absolute;
      left: -1.4rem;
      top: 0.55rem;
      width: 0.4rem;
      height: 0.4rem;
      border-radius: 50%;
      background: var(--plotted-border-strong);
    }

    /* The last stage is the one the product is actually about. */
    .pipeline li:last-child::before {
      background: var(--plotted-accent);
    }

    .step {
      display: inline-block;
      min-width: 5.5rem;
      color: var(--plotted-text);
    }

    .stack {
      color: var(--plotted-text-muted);
      font-size: 0.72rem;
      line-height: 1.9;
      text-transform: none;
      letter-spacing: 0.04em;
    }

    .status {
      list-style: none;
      margin: 0 0 1rem;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      font-size: 0.92rem;
    }

    .status li {
      display: grid;
      grid-template-columns: 0.55rem minmax(0, 1fr);
      gap: 0.75rem;
      align-items: start;
      color: var(--plotted-text-faint);
    }

    .status li::before {
      content: '';
      width: 0.5rem;
      height: 0.5rem;
      margin-top: 0.45rem;
      border-radius: 50%;
      border: 1.5px solid var(--plotted-border-strong);
    }

    .status li.done {
      color: var(--plotted-text-muted);
    }

    .status li.done::before {
      background: var(--plotted-accent);
      border-color: var(--plotted-accent);
    }

    /* Half-filled, because "built but not yet meaningful" is neither done nor
       outstanding, and collapsing it into either would misreport the project. */
    .status li.partial::before {
      background: linear-gradient(
        90deg,
        var(--plotted-accent) 50%,
        transparent 50%
      );
      border-color: var(--plotted-accent);
    }

    .note {
      font-size: 0.82rem;
    }

    .data a {
      color: var(--plotted-accent);
      text-decoration: none;
      font-size: 0.92rem;
    }

    .data a:hover {
      text-decoration: underline;
    }
  `,
})
export class AboutPage {}

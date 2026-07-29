import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * What Plotted is, and how far along it is.
 *
 * This lives away from the home screen on purpose. Build status is interesting
 * to someone evaluating the project and irrelevant to someone who just wants to
 * watch something — and the home screen belongs to the second person.
 */
@Component({
  selector: 'plotted-about',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="intro">
      <p class="eyebrow">About</p>
      <h1>Plotted works for you, not for a platform.</h1>
      <p class="lede muted">
        Every streaming app recommends from its own catalogue. Netflix will not tell you the best
        thing tonight is on Crave, and Prime will not suggest cancelling Prime. Plotted is
        platform-neutral, which is the only thing about it that is hard to copy.
      </p>
    </section>

    <div class="columns">
      <section class="panel">
        <h2>What it will do</h2>
        <dl class="features">
          <dt>Queue Theory</dt>
          <dd>
            One recommendation, two backups, and a reason for each &mdash; from your time, mood
            and what you already pay for.
          </dd>
          <dt>Cancel Culture</dt>
          <dd>
            A subscription optimiser: which services to keep, pause or drop next month, and what
            each constraint is costing you.
          </dd>
          <dt>Plot Armour</dt>
          <dd>
            Notices when a watchlist title leaves a service, and estimates the risk that one is
            about to.
          </dd>
          <dt>End Credits</dt>
          <dd>Whether any of it is actually working: cost per finished hour, decision time.</dd>
        </dl>
      </section>

      <section class="panel">
        <h2>How far along</h2>
        <ol class="phases">
          <li class="done"><span>Accounts, sessions and the Canadian schema</span></li>
          <li class="done"><span>Catalogue, availability and nightly snapshots</span></li>
          <li><span>Watchlists and platform coverage</span></li>
          <li><span>Queue Theory &mdash; tonight&rsquo;s pick</span></li>
          <li><span>Cancel Culture &mdash; next month&rsquo;s subscriptions</span></li>
        </ol>
        <p class="faint note">
          There is nothing to recommend yet, so nothing here pretends to recommend anything.
        </p>
      </section>
    </div>

    <section class="panel data">
      <h2>Where the data comes from</h2>
      <p class="muted">
        Title metadata and Canadian streaming availability come from TMDB and JustWatch. That data
        is imperfect, so every availability claim in Plotted shows its source and when it was last
        checked, and stale prices are hidden rather than shown.
      </p>
      <p>
        <a routerLink="/legal/data-sources">Sources, terms and refresh cadence &rarr;</a>
      </p>
    </section>
  `,
  styles: `
    .intro {
      max-width: 42rem;
      margin-bottom: 2.5rem;
    }

    .intro h1 {
      margin: 0.35rem 0 0.75rem;
    }

    .lede {
      font-size: 1.02rem;
      margin: 0;
    }

    .columns {
      display: grid;
      gap: 1rem;
      grid-template-columns: repeat(auto-fit, minmax(20rem, 1fr));
      margin-bottom: 1rem;
    }

    .features {
      margin: 0;
      font-size: 0.9rem;
    }

    .features dt {
      font-weight: 600;
      margin-top: 0.9rem;
    }

    .features dt:first-child {
      margin-top: 0;
    }

    .features dd {
      margin: 0.15rem 0 0;
      color: var(--plotted-text-muted);
    }

    .phases {
      list-style: none;
      counter-reset: phase;
      padding: 0;
      margin: 0 0 1rem;
      font-size: 0.9rem;
    }

    .phases li {
      counter-increment: phase;
      display: flex;
      align-items: baseline;
      gap: 0.7rem;
      padding: 0.3rem 0;
      color: var(--plotted-text-faint);
    }

    .phases li::before {
      content: counter(phase);
      flex-shrink: 0;
      width: 1.35rem;
      height: 1.35rem;
      border-radius: 999px;
      border: 1px solid var(--plotted-border-strong);
      display: grid;
      place-items: center;
      font-size: 0.7rem;
      font-variant-numeric: tabular-nums;
    }

    .phases li.done {
      color: var(--plotted-text);
    }

    .phases li.done::before {
      content: '✓';
      background: var(--plotted-accent-soft);
      border-color: transparent;
      color: var(--plotted-accent);
    }

    .note {
      font-size: 0.8rem;
      margin: 0;
    }

    .data {
      max-width: 42rem;
    }

    .data p:last-child {
      margin: 0;
    }

    .data a {
      color: var(--plotted-accent);
      text-decoration: none;
      font-size: 0.9rem;
    }

    .data a:hover {
      text-decoration: underline;
    }
  `,
})
export class AboutPage {}

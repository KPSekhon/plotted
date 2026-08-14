import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Runtime as distance to a destination, with your evening drawn across it.
 *
 * The point of the bar is not decoration: `1 h 47 m` is a fact you have to do
 * arithmetic on before it answers the question you actually asked, which is
 * "can I finish this tonight?". Putting the window on the same axis answers it
 * without the reader doing anything.
 *
 * ### It is distance, not progress
 *
 * Nothing here animates and nothing fills up. A progress bar would imply
 * Plotted knows how far through this you are, and it does not — `watchlist_items`
 * carries a coarse status and no episode position. The moment this looks like
 * playback it is claiming knowledge that does not exist.
 *
 * ### Series get no position marker at all
 *
 * A series is not watched in one sitting, so "distance to the credits" is the
 * wrong question and a "you are here" marker would have nothing true to point
 * at. What is shown instead is the catalogue's own structure — how many
 * episodes, and what the whole thing costs in hours — which is real, and is
 * the number somebody deciding whether to start a series actually wants.
 *
 * ### The window comes from settings, not from a guess
 *
 * `defaultAvailableMinutes` is a figure the user typed. When it is absent the
 * boundary is simply not drawn — the bar still shows the runtime, and no
 * default evening is invented on their behalf.
 */
@Component({
  selector: 'plotted-runtime-route',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="runtime" aria-labelledby="runtime-heading">
      <h3 id="runtime-heading" class="coordinates">
        {{ isSeries() ? 'Commitment' : 'Runtime' }}
      </h3>

      @if (watchMinutes(); as minutes) {
        @if (isSeries()) {
          <p class="series readout">
            @if (episodeCount(); as episodes) {
              {{ episodes }} {{ episodes === 1 ? 'episode' : 'episodes' }} &middot;
            }
            {{ format(minutes) }} in total
          </p>

          <!-- A neutral track and nothing on it. Drawing a marker here would be
               inventing a viewing position Plotted does not store. -->
          <div class="track series-track" aria-hidden="true"></div>

          <p class="note faint">
            Plotted does not track which episode you are on, so there is nothing to mark.
          </p>
        } @else {
          <div class="bar" aria-hidden="true">
            <span class="label start coordinates">Now</span>

            <div class="track">
              <span class="plot-point origin"></span>
              <span class="run" [style.width.%]="creditsAt()"></span>

              @if (boundaryAt() !== null) {
                <span class="boundary" [style.left.%]="boundaryAt()"></span>
              }

              <span class="credits" [style.left.%]="creditsAt()">
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round">
                  <path d="M2 2l8 8" />
                  <path d="M10 2l-8 8" />
                </svg>
              </span>
            </div>

            <span class="label end coordinates">Credits</span>
          </div>

          <p class="figures">
            <span class="readout">{{ format(minutes) }}</span>
            @if (verdict(); as sentence) {
              <span class="verdict" [class.over]="overruns()">{{ sentence }}</span>
            }
          </p>
        }
      } @else {
        <p class="note faint">
          Plotted does not know how long this is yet, so it cannot be promised to fit a
          time window.
        </p>
      }
    </section>
  `,
  styles: `
    .runtime {
      margin: 1.5rem 0;
      display: grid;
      gap: 0.5rem;
    }

    h3 {
      margin: 0;
    }

    .bar {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.6rem;
    }

    .label {
      font-size: 0.6rem;
      white-space: nowrap;
    }

    .track {
      position: relative;
      height: 1px;
      background: var(--plotted-border-strong);
    }

    .series-track {
      background: var(--plotted-border-strong);
      margin: 0.35rem 0 0.1rem;
    }

    .origin {
      position: absolute;
      left: 0;
      top: 50%;
      margin: -0.25rem 0 0 0;
    }

    /* The travelled distance. Orange because the title page is about this one
       title -- it is the route under discussion, not one option among several. */
    .run {
      position: absolute;
      left: 0;
      top: 0;
      height: 1px;
      background: var(--plotted-accent);
    }

    .credits {
      position: absolute;
      top: 50%;
      width: 0.7rem;
      height: 0.7rem;
      margin: -0.35rem 0 0 -0.35rem;
      color: var(--plotted-accent);

      svg { width: 100%; height: 100%; }
    }

    /* The evening, as a boundary the route either reaches or crosses. Dashed
       and neutral: it is a limit, not a destination. */
    .boundary {
      position: absolute;
      top: -0.5rem;
      bottom: -0.5rem;
      width: 0;
      border-left: 1.5px dashed var(--plotted-text-faint);
    }

    .figures {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0.25rem 0.75rem;
      margin: 0;
      font-size: 0.82rem;
    }

    .verdict {
      color: var(--plotted-text-muted);
    }

    /* Status, not brand: overrunning your evening is a warning about a fit, and
       colouring it orange would say Plotted chose it. */
    .verdict.over {
      color: var(--plotted-warning);
    }

    .series {
      margin: 0;
      font-size: 0.85rem;
      color: var(--plotted-text-muted);
    }

    .note {
      margin: 0;
      font-size: 0.76rem;
      max-width: 32rem;
    }
  `,
})
export class RuntimeRouteComponent {
  readonly watchMinutes = input<number | null>(null);
  readonly episodeCount = input<number | null>(null);
  readonly isSeries = input(false);

  /** The user's own stated evening, from settings. Null means do not draw one. */
  readonly availableMinutes = input<number | null>(null);

  /**
   * The window, or null unless it is a usable positive number.
   *
   * `=== null` was not enough and shipped a visible bug: the settings endpoint
   * omits the field rather than sending null, so `undefined` sailed past the
   * guard and the page rendered "finishes with NaN h NaN min to spare". Every
   * consumer below goes through this, so there is one place that decides what
   * counts as a window rather than three that each nearly agree.
   */
  private readonly window = computed<number | null>(() => {
    const value = this.availableMinutes();
    return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
  });

  private readonly runtime = computed<number | null>(() => {
    const value = this.watchMinutes();
    return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
  });

  /**
   * The axis spans whichever is longer, so both markers are always on it.
   *
   * Scaling to the runtime alone would push the boundary off the end whenever
   * a title comfortably fits — which is exactly the case where the reader most
   * wants to see the gap.
   */
  private readonly span = computed(() =>
    Math.max(this.runtime() ?? 0, this.window() ?? 0, 1),
  );

  protected readonly creditsAt = computed(() => ((this.runtime() ?? 0) / this.span()) * 100);

  protected readonly boundaryAt = computed(() => {
    const window = this.window();
    return window === null ? null : (window / this.span()) * 100;
  });

  protected readonly overruns = computed(() => {
    const window = this.window();
    const runtime = this.runtime();
    return window !== null && runtime !== null && runtime > window;
  });

  protected readonly verdict = computed<string | null>(() => {
    const window = this.window();
    const runtime = this.runtime();
    if (window === null || runtime === null) return null;

    const difference = Math.abs(runtime - window);
    if (difference === 0) return 'exactly your usual evening';
    return this.overruns()
      ? `${this.format(difference)} past your usual evening`
      : `finishes with ${this.format(difference)} to spare`;
  });

  protected format(minutes: number): string {
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
  }
}

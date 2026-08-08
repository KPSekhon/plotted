import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Alert } from '../../core/alerts/alerts.models';
import { API_BASE_URL } from '../../core/api/api.config';
import { AlertListComponent } from './alert-list.component';

/**
 * Plot Armour's alert list, designed against fixtures because nothing real
 * exists.
 *
 * No alert has ever fired in production: `availability.removed` is emitted only
 * by the nightly refresh, which needs `PLOTTED_SNAPSHOT_ENABLED` and an
 * environment that runs continuously. Waiting for real content before designing
 * the component would mean designing it under time pressure later, so these are
 * the states it has to survive — realistic message lengths, every severity, and
 * both event kinds.
 */
describe('AlertListComponent', () => {
  let fixture: ComponentFixture<AlertListComponent>;
  let http: HttpTestingController;

  /**
   * The development story set. Message wording matches what the API composes.
   *
   * `satisfies` rather than a `Record` annotation, so each fixture is still
   * checked against `Alert` while the keys stay concrete — the project has
   * `noPropertyAccessFromIndexSignature` on, and an index signature would force
   * every reference into bracket notation for no benefit.
   */
  const fixtures = {
    left: {
      id: '1',
      alertType: 'availability.left',
      severity: 'warning',
      titleId: 'title-1',
      message: 'Barry has left Crave.',
      createdAt: '2026-08-07T20:41:00Z',
    },
    urgentLeft: {
      id: '2',
      alertType: 'availability.removed',
      severity: 'urgent',
      titleId: 'title-2',
      message:
        'The Last of Us has left Crave, and it was the only service on your list carrying it.',
      createdAt: '2026-08-07T20:42:00Z',
    },
    added: {
      id: '3',
      alertType: 'availability.added',
      severity: 'info',
      titleId: 'title-3',
      message: 'Severance is now on Apple TV+.',
      createdAt: '2026-08-07T20:43:00Z',
    },
    untitled: {
      id: '4',
      alertType: 'account.notice',
      severity: 'info',
      titleId: null,
      message: 'Your demo account expires tomorrow.',
      createdAt: '2026-08-07T20:44:00Z',
    },
  } satisfies Record<string, Alert>;

  async function render(alerts: Alert[]) {
    await TestBed.configureTestingModule({
      imports: [AlertListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: API_BASE_URL, useValue: '/api/v1' },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AlertListComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/alerts').flush({ alerts });
    fixture.detectChanges();
    return fixture;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('renders absolutely nothing when there is nothing to say', async () => {
    await render([]);

    // Plot Armour's whole design is suppressing alerts not worth sending. A
    // permanent "no alerts" panel would put the feature on screen every day for
    // the exact outcome it works hardest to produce.
    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
    expect(fixture.nativeElement.querySelector('.alerts')).toBeNull();
  });

  it('marks a departure as a dead end rather than by colour alone', async () => {
    await render([fixtures.left]);

    // The shape carries the meaning so the alert still reads correctly to
    // somebody who cannot tell the warning gold from the critical red.
    const icon = fixture.nativeElement.querySelector('plotted-icon svg');
    expect(icon).toBeTruthy();
    expect(text()).toContain('Left a service');
  });

  it('does not use a dead end for a title becoming available', async () => {
    await render([fixtures.added]);

    expect(text()).toContain('Now available');
    expect(text()).not.toContain('Left a service');
  });

  it('keeps severity out of the brand accent', async () => {
    await render([fixtures.left, fixtures.urgentLeft]);

    const [warning, urgent] = fixture.nativeElement.querySelectorAll('.alert');
    expect(warning.classList).toContain('warning');
    expect(urgent.classList).toContain('urgent');

    // Orange means "Plotted chose this" everywhere else in the product. If it
    // also meant "warning" it would mean neither.
    const styles = getComputedStyle(warning).borderLeftColor;
    expect(styles).not.toBe('rgb(255, 100, 26)');
  });

  it('offers dismiss on every alert, including one with no title to link to', async () => {
    await render([fixtures.untitled]);

    // Dismissing is answering, and the API takes it as a 60-day answer for that
    // title, so it has to be easy to give rather than buried.
    const dismiss = fixture.nativeElement.querySelector('button[aria-label^="Dismiss"]');
    expect(dismiss).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.message a')).toBeNull();
  });

  it('survives a failed load without breaking the page it sits on', async () => {
    await TestBed.configureTestingModule({
      imports: [AlertListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: API_BASE_URL, useValue: '/api/v1' },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AlertListComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/alerts').error(new ProgressEvent('network'));
    fixture.detectChanges();

    // Home renders this above the hero. Nothing here is load-bearing enough to
    // justify an error banner on somebody's landing page.
    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
  });
});

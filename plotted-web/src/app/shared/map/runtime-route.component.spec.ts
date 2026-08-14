import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RuntimeRouteComponent } from './runtime-route.component';

/**
 * The runtime bar, and mainly the guard on the window.
 *
 * The first version checked `window === null` and shipped a visible defect:
 * the settings endpoint omits `defaultAvailableMinutes` rather than sending
 * null, so `undefined` passed the guard and the page rendered
 * "finishes with NaN h NaN min to spare" against a real title.
 *
 * That is the shape of bug this project keeps finding — a check that reads as
 * correct, passes review, and is wrong about one input it was never shown. So
 * the absent cases are asserted here rather than assumed.
 */
describe('RuntimeRouteComponent', () => {
  let fixture: ComponentFixture<RuntimeRouteComponent>;

  async function render(inputs: Record<string, unknown>) {
    await TestBed.configureTestingModule({ imports: [RuntimeRouteComponent] }).compileComponents();
    fixture = TestBed.createComponent(RuntimeRouteComponent);
    Object.entries(inputs).forEach(([key, value]) => fixture.componentRef.setInput(key, value));
    fixture.detectChanges();
    return fixture;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('says nothing about a window that was never set', async () => {
    await render({ watchMinutes: 105, availableMinutes: null });

    expect(text()).toContain('1 h 45 min');
    expect(text()).not.toContain('spare');
    expect(fixture.nativeElement.querySelector('.boundary')).toBeNull();
  });

  it('treats an absent window the same as an unset one', async () => {
    // The regression. `undefined` is what the settings endpoint actually sends
    // when the user has not chosen a default evening.
    await render({ watchMinutes: 105, availableMinutes: undefined });

    expect(text()).not.toContain('NaN');
    expect(text()).not.toContain('spare');
    expect(fixture.nativeElement.querySelector('.boundary')).toBeNull();
  });

  it('reports the shortfall when a film runs past the evening', async () => {
    await render({ watchMinutes: 107, availableMinutes: 90 });

    expect(text()).toContain('17 min past your usual evening');
    // Status colour, not the accent: overrunning is a warning about fit, and
    // orange would say Plotted chose it.
    expect(fixture.nativeElement.querySelector('.verdict.over')).toBeTruthy();
  });

  it('reports the slack when it fits, and keeps the boundary on the axis', async () => {
    await render({ watchMinutes: 90, availableMinutes: 120 });

    expect(text()).toContain('finishes with 30 min to spare');

    // The axis spans the longer of the two, so a comfortable fit still shows
    // the gap rather than pushing the window off the end of the bar.
    const boundary = fixture.nativeElement.querySelector('.boundary') as HTMLElement;
    expect(boundary).toBeTruthy();
    expect(boundary.style.left).toBe('100%');
  });

  it('gives a series no position marker, because none is known', async () => {
    await render({ watchMinutes: 660, episodeCount: 24, isSeries: true });

    expect(text()).toContain('24 episodes');
    expect(text()).toContain('11 h');
    // A "you are here" marker would be inventing a viewing position that
    // watchlist_items does not store.
    expect(fixture.nativeElement.querySelector('.credits')).toBeNull();
    expect(fixture.nativeElement.querySelector('.origin')).toBeNull();
    expect(text()).toContain('does not track which episode you are on');
  });

  it('says so plainly when the runtime itself is unknown', async () => {
    await render({ watchMinutes: null });

    expect(text()).toContain('does not know how long this is');
    expect(fixture.nativeElement.querySelector('.track')).toBeNull();
  });
});

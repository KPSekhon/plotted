import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MonthPlan, PlanCoveredTitle } from '../../core/plan/plan.models';
import { PlanTransitMapComponent } from './plan-transit-map.component';

/**
 * The transit map's row building, tested because it cannot be seen here.
 *
 * CP-SAT is a JNI binding that crashes the JVM on the Windows development
 * machine — driving `/api/v1/plan` once was enough to take the API down with an
 * `EXCEPTION_ACCESS_VIOLATION`, exactly as `docs/PROGRESS.md` warned. So this
 * component can never be checked against a real solved plan locally, and
 * "it looked right" is not available as evidence.
 *
 * What is left is the part that could actually be wrong: turning a list of
 * months into a list of service routes. Every assertion below is a case where
 * a plausible implementation draws a misleading picture — a service missing a
 * row, a cancellation shown as a subscription, a title captioning the wrong
 * month.
 */
describe('PlanTransitMapComponent', () => {
  let fixture: ComponentFixture<PlanTransitMapComponent>;

  const crave = 'crave-id';
  const netflix = 'netflix-id';

  function month(
    index: number,
    subscribed: string[] = [],
    started: string[] = [],
    stopped: string[] = [],
  ): MonthPlan {
    const ref = (id: string) => ({ providerId: id, name: id === crave ? 'Crave' : 'Netflix' });
    return {
      month: index,
      monthlyCents: subscribed.length * 1999,
      subscribed: subscribed.map(ref),
      started: started.map(ref),
      stopped: stopped.map(ref),
    };
  }

  async function render(months: MonthPlan[], covered: PlanCoveredTitle[] = []) {
    await TestBed.configureTestingModule({ imports: [PlanTransitMapComponent] }).compileComponents();
    fixture = TestBed.createComponent(PlanTransitMapComponent);
    fixture.componentRef.setInput('months', months);
    fixture.componentRef.setInput('covered', covered);
    fixture.detectChanges();
    return fixture;
  }

  it('gives a row to a service that is only ever cancelled', async () => {
    // The service is held in no month at all -- it is stopped in month 0 and
    // absent thereafter. Building rows from `subscribed` alone, which is the
    // obvious implementation, would drop it entirely and the map would show a
    // cancellation happening to nothing.
    await render([month(0, [], [], [netflix]), month(1)]);

    const names = fixture.nativeElement.querySelectorAll('.row:not(.header) .name');
    expect(names.length).toBe(1);
    expect(names[0].textContent).toContain('Netflix');
  });

  it('marks a start and a cancellation differently', async () => {
    await render([month(0, [crave], [crave]), month(1, [], [], [crave])]);

    const cells = fixture.nativeElement.querySelectorAll('.cell');
    // Start and stop are separate variables in the model, because starting
    // costs money you were not spending and stopping costs access you had.
    // Drawing them with one marker would erase a distinction the solver goes
    // out of its way to compute.
    expect(cells[0].querySelector('.marker.start')).toBeTruthy();
    expect(cells[1].querySelector('.marker.stop')).toBeTruthy();
    expect(cells[1].classList).not.toContain('held');
  });

  it('draws the faint track across months the service is not held', async () => {
    await render([month(0), month(1, [crave], [crave]), month(2, [crave])]);

    const cells = fixture.nativeElement.querySelectorAll('.cell');
    // The neutral line is the planning horizon. Without it the orange would be
    // the only thing on the row and would read as the whole picture rather
    // than as a selection out of the months available.
    expect(cells.length).toBe(3);
    expect(cells[0].classList).not.toContain('held');
    expect(cells[1].classList).toContain('held');
    expect(cells[2].classList).toContain('held');
  });

  it('captions a start with the titles it covers, and nothing else', async () => {
    const covered: PlanCoveredTitle[] = [
      { titleId: 't1', name: 'Barry', month: 1, providerId: crave, providerName: 'Crave' },
      { titleId: 't2', name: 'Hacks', month: 1, providerId: crave, providerName: 'Crave' },
      { titleId: 't3', name: 'The Last of Us', month: 1, providerId: crave, providerName: 'Crave' },
      // Month 2 is a continuation, not a start, so these must not be captioned.
      { titleId: 't4', name: 'Succession', month: 2, providerId: crave, providerName: 'Crave' },
    ];
    await render([month(0), month(1, [crave], [crave]), month(2, [crave])], covered);

    const cells = fixture.nativeElement.querySelectorAll('.cell');
    const startCaption = cells[1].querySelector('.titles');

    // At most two: the question a start raises is "why subscribe to this", and
    // two titles answer it where forty would bury the routes.
    expect(startCaption.textContent).toContain('Barry');
    expect(startCaption.textContent).toContain('Hacks');
    expect(startCaption.textContent).not.toContain('The Last of Us');

    // Only at a start. A caption on every held month turns the map into a
    // table with extra steps.
    expect(cells[2].querySelector('.titles')).toBeNull();
  });

  it('orders services by how long they are held, so the plan reads top down', async () => {
    await render([
      month(0, [crave, netflix], [crave, netflix]),
      month(1, [crave], [], [netflix]),
      month(2, [crave]),
    ]);

    const names = [...fixture.nativeElement.querySelectorAll('.row:not(.header) .name')].map(
      (element: Element) => element.textContent?.trim() ?? '',
    );
    expect(names[0]).toContain('Crave');
    expect(names[1]).toContain('Netflix');
  });

  it('reports months held against the horizon rather than as a bare count', async () => {
    await render([month(0, [crave], [crave]), month(1, [crave]), month(2)]);

    const held = fixture.nativeElement.querySelector('.row:not(.header) .held');
    // "2 mo" alone invites the reader to guess the denominator. Two of three is
    // a very different plan from two of twelve.
    expect(held.textContent.replace(/\s+/g, ' ').trim()).toBe('2/3 mo');
  });
});

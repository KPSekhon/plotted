import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { DemoNoteComponent } from './demo-note.component';

/**
 * The disclosure is a truth mechanism, so the case that matters is the one
 * where it must *not* appear.
 *
 * A note that renders for everybody is a blanket disclaimer people learn to
 * skip, and it would put "sample data" under numbers that were genuinely
 * measured — which is the same class of false statement, pointed the other way.
 * Both directions are asserted here because only one of them is visible while
 * developing against a demo account, which is what every session on this
 * machine has been.
 */
@Component({
  standalone: true,
  imports: [DemoNoteComponent],
  template: '<plotted-demo-note>Computed from generated activity.</plotted-demo-note>',
})
class HostComponent {}

describe('DemoNoteComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  const account = (isDemo: boolean): User => ({
    id: '11111111-2222-3333-4444-555555555555',
    email: 'someone@example.invalid',
    displayName: 'Someone',
    regionCode: 'CA',
    timezone: 'America/Toronto',
    preferredCurrency: 'CAD',
    onboardingStatus: 'active',
    createdAt: '2026-07-26T18:00:00Z',
    isDemo,
  });

  async function render(user: User | null) {
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [{ provide: AuthService, useValue: { user: signal(user) } }],
    }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('marks the content as sample data on a demo account', async () => {
    const text = await render(account(true));

    expect(text).toContain('Sample data');
    expect(text).toContain('Computed from generated activity.');
  });

  it('renders nothing at all on a real account', async () => {
    const text = await render(account(false));

    // Not merely "no badge": the projected sentence must not appear either, or
    // a real user reads a caption about generated activity under their own
    // figures.
    expect(text).not.toContain('Sample data');
    expect(text).not.toContain('Computed from generated activity.');
  });

  it('renders nothing when there is no session yet', async () => {
    // The signed-out and still-restoring cases both land here. Defaulting to
    // showing the note would flash "sample data" over a real account's screen
    // for as long as the refresh takes.
    const text = await render(null);

    expect(text).not.toContain('Sample data');
  });
});

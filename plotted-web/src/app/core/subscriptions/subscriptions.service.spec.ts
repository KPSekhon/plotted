import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { API_BASE_URL } from '../api/api.config';
import { Provider, SubscriptionList } from './subscriptions.models';
import { SubscriptionsService } from './subscriptions.service';

describe('SubscriptionsService', () => {
  let service: SubscriptionsService;
  let http: HttpTestingController;
  const baseUrl = '/api/v1';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: baseUrl },
      ],
    });
    service = TestBed.inject(SubscriptionsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('unwraps the provider list so callers do not handle the envelope', () => {
    let received: readonly Provider[] | undefined;
    service.providers().subscribe((providers) => (received = providers));

    const request = http.expectOne(`${baseUrl}/providers`);
    request.flush({
      providers: [{ id: 'p1', name: 'Crave', slug: 'crave', type: 'subscription', logoUrl: null }],
    });

    expect(received?.length).toBe(1);
    expect(received?.[0].name).toBe('Crave');
  });

  it('carries the price the caller supplied without transforming it', () => {
    service.add({ providerId: 'p1', price: 14.49, billingPeriod: 'monthly' }).subscribe();

    const request = http.expectOne(`${baseUrl}/subscriptions`);
    expect(request.request.method).toBe('POST');
    // The user's own figure reaches the server untouched. Rounding or defaulting
    // it here would put a number nobody typed into the optimiser's objective.
    expect(request.request.body.price).toBe(14.49);
    request.flush({});
  });

  it('reads the monthly total and the count it is based on', () => {
    let received: SubscriptionList | undefined;
    service.list().subscribe((list) => (received = list));

    http.expectOne(`${baseUrl}/subscriptions`).flush({
      subscriptions: [],
      monthlyTotal: 29.99,
      currency: 'CAD',
      // Fewer than the number of subscriptions returned: cancelled rows are kept
      // in the record but cost nothing, and the total says so.
      countedSubscriptions: 2,
    });

    expect(received?.monthlyTotal).toBe(29.99);
    expect(received?.countedSubscriptions).toBe(2);
  });
});

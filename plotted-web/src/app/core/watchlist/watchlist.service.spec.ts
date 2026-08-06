import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { API_BASE_URL } from '../api/api.config';
import { Coverage, Watchlist } from './watchlist.models';
import { WatchlistService } from './watchlist.service';

describe('WatchlistService', () => {
  let service: WatchlistService;
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
    service = TestBed.inject(WatchlistService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks for the watchlist without passing any id', () => {
    let received: Watchlist | undefined;
    service.get().subscribe((watchlist) => (received = watchlist));

    // The absence of an id in this URL is the point: the server resolves the
    // caller from its token, so there is no id a client could tamper with.
    const request = http.expectOne(`${baseUrl}/watchlist`);
    expect(request.request.method).toBe('GET');
    request.flush({ id: 'w1', name: 'My list', items: [] });

    expect(received?.items).toEqual([]);
  });

  it('sends only the fields being changed when patching an item', () => {
    service.update('item-1', { priority: 1 }).subscribe();

    const request = http.expectOne(`${baseUrl}/watchlist/items/item-1`);
    expect(request.request.method).toBe('PATCH');
    // Omitted fields must stay omitted: sending nulls would clear values the
    // user never touched.
    expect(request.request.body).toEqual({ priority: 1 });
    request.flush({});
  });

  it('reads coverage, including the count it deliberately did not score', () => {
    let received: Coverage | undefined;
    service.coverage().subscribe((coverage) => (received = coverage));

    const request = http.expectOne(`${baseUrl}/watchlist/coverage`);
    request.flush({
      regionCode: 'CA',
      consideredTitles: 3,
      unknownTitles: 2,
      providers: [],
      attribution: 'Streaming availability data provided by JustWatch via TMDB.',
    });

    // unknownTitles is carried through rather than collapsed into the total.
    // The dashboard has to be able to say "we have not checked these" instead
    // of scoring them as uncovered.
    expect(received?.consideredTitles).toBe(3);
    expect(received?.unknownTitles).toBe(2);
  });
});

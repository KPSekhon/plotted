import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { CatalogueService } from './catalogue.service';

describe('CatalogueService', () => {
  let service: CatalogueService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CatalogueService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('searches the local catalogue', () => {
    service.search('severance').subscribe();

    const request = http.expectOne(
      (r) => r.url === '/api/v1/titles/search' && r.params.get('query') === 'severance',
    );

    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('sends the media type filter in the form Spring binds enums from', () => {
    service.search('dune', 'movie').subscribe();

    const request = http.expectOne((r) => r.url === '/api/v1/titles/search');

    // Spring binds request-param enums by constant name, uppercase; the JSON
    // body convention is lowercase. Two channels, two casings — asserted so a
    // refactor cannot quietly break one of them.
    expect(request.request.params.get('mediaType')).toBe('MOVIE');
    request.flush([]);
  });

  it('sends the lowercase form in an ingest body', () => {
    service.ingest({ mediaType: 'movie', tmdbId: 438631 }).subscribe();

    const request = http.expectOne('/api/v1/titles');

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ mediaType: 'movie', tmdbId: 438631 });
    request.flush({});
  });

  it('discover and search are different endpoints, not a flag', () => {
    service.discover('dune').subscribe();

    const request = http.expectOne((r) => r.url === '/api/v1/titles/discover');
    request.flush([]);
  });

  it('fetches availability for a title', () => {
    service.availability('11111111-2222-3333-4444-555555555555').subscribe();

    http
      .expectOne('/api/v1/titles/11111111-2222-3333-4444-555555555555/availability')
      .flush({ regionCode: 'CA', offers: [], lastVerifiedAt: null, stale: true, attribution: '' });
  });
});

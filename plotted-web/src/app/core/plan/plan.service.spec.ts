import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { API_BASE_URL } from '../api/api.config';
import { PlanQuery } from './plan.models';
import { PlanService } from './plan.service';

describe('PlanService', () => {
  let service: PlanService;
  let http: HttpTestingController;
  const baseUrl = '/api/v1';

  const noLimits: PlanQuery = {
    horizonMonths: 6,
    maximumMonthlyCents: null,
    maximumActiveServices: null,
    maximumMonthlySwitches: null,
    coverageWeight: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: baseUrl },
      ],
    });
    service = TestBed.inject(PlanService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('omits limits the user did not set rather than sending zero', () => {
    service.plan(noLimits).subscribe();

    const request = http.expectOne((candidate) => candidate.url === `${baseUrl}/plan`);
    // A zero budget is a real and much narrower request than "no budget", and
    // sending one by accident would quietly answer a different question.
    expect(request.request.params.has('maximumMonthlyCents')).toBe(false);
    expect(request.request.params.has('maximumActiveServices')).toBe(false);
    expect(request.request.params.has('maximumMonthlySwitches')).toBe(false);
    expect(request.request.params.get('horizonMonths')).toBe('6');
    request.flush({});
  });

  it('sends a zero switch limit, because changing nothing is a real request', () => {
    service.plan({ ...noLimits, maximumMonthlySwitches: 0 }).subscribe();

    const request = http.expectOne((candidate) => candidate.url === `${baseUrl}/plan`);
    expect(request.request.params.get('maximumMonthlySwitches')).toBe('0');
    request.flush({});
  });

  it('passes every limit through untransformed', () => {
    service
      .plan({
        horizonMonths: 3,
        maximumMonthlyCents: 4500,
        maximumActiveServices: 2,
        maximumMonthlySwitches: 1,
        coverageWeight: 0.8,
      })
      .subscribe();

    const request = http.expectOne((candidate) => candidate.url === `${baseUrl}/plan`);
    expect(request.request.params.get('maximumMonthlyCents')).toBe('4500');
    expect(request.request.params.get('maximumActiveServices')).toBe('2');
    expect(request.request.params.get('coverageWeight')).toBe('0.8');
    request.flush({});
  });
});

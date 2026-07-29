import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import {
  Availability,
  DiscoverResult,
  IngestTitleRequest,
  MediaType,
  Title,
} from './catalogue.models';

@Injectable({ providedIn: 'root' })
export class CatalogueService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  /** Titles Plotted already has. Fast, and every result can be acted on. */
  search(query: string, mediaType?: MediaType): Observable<Title[]> {
    let params = new HttpParams().set('query', query);
    if (mediaType) {
      params = params.set('mediaType', mediaType.toUpperCase());
    }
    return this.http.get<Title[]>(`${this.baseUrl}/titles/search`, { params });
  }

  /**
   * Asks TMDB. Someone adding to a watchlist wants everything that exists, not
   * only what has been ingested, so this is a separate call with results that
   * cannot be acted on until {@link ingest} gives them a Plotted identifier.
   */
  discover(query: string): Observable<DiscoverResult[]> {
    return this.http.get<DiscoverResult[]>(`${this.baseUrl}/titles/discover`, {
      params: new HttpParams().set('query', query),
    });
  }

  get(titleId: string): Observable<Title> {
    return this.http.get<Title>(`${this.baseUrl}/titles/${titleId}`);
  }

  /** Idempotent: ingesting a title Plotted already has refreshes it in place. */
  ingest(request: IngestTitleRequest): Observable<Title> {
    return this.http.post<Title>(`${this.baseUrl}/titles`, request);
  }

  availability(titleId: string): Observable<Availability> {
    return this.http.get<Availability>(`${this.baseUrl}/titles/${titleId}/availability`);
  }
}

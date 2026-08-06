import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import {
  AddWatchlistItemRequest,
  Coverage,
  UpdateWatchlistItemRequest,
  Watchlist,
  WatchlistItem,
} from './watchlist.models';

/**
 * The watchlist API.
 *
 * No endpoint takes a watchlist id: the server resolves the caller to its own
 * default list from the access token, so there is no id here to get wrong.
 */
@Injectable({ providedIn: 'root' })
export class WatchlistService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  get(): Observable<Watchlist> {
    return this.http.get<Watchlist>(`${this.baseUrl}/watchlist`);
  }

  /** Idempotent: adding a title already on the list returns the existing entry. */
  add(request: AddWatchlistItemRequest): Observable<WatchlistItem> {
    return this.http.post<WatchlistItem>(`${this.baseUrl}/watchlist/items`, request);
  }

  update(itemId: string, request: UpdateWatchlistItemRequest): Observable<WatchlistItem> {
    return this.http.patch<WatchlistItem>(`${this.baseUrl}/watchlist/items/${itemId}`, request);
  }

  remove(itemId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/watchlist/items/${itemId}`);
  }

  coverage(): Observable<Coverage> {
    return this.http.get<Coverage>(`${this.baseUrl}/watchlist/coverage`);
  }
}

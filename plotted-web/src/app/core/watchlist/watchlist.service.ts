import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import {
  AddWatchlistItemRequest,
  BlockTitleRequest,
  BlockedTitle,
  BlockedTitles,
  Coverage,
  SeriesProgress,
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

  /** What the user has blocked. Fetching this is what makes blocking reversible. */
  blocked(): Observable<BlockedTitles> {
    return this.http.get<BlockedTitles>(`${this.baseUrl}/watchlist/blocked`);
  }

  /** Idempotent: blocking twice returns the original block, reason and timestamp included. */
  block(request: BlockTitleRequest): Observable<BlockedTitle> {
    return this.http.post<BlockedTitle>(`${this.baseUrl}/watchlist/blocked`, request);
  }

  unblock(titleId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/watchlist/blocked/${titleId}`);
  }

  /** Where you are in a series, and what is next. */
  progress(titleId: string): Observable<SeriesProgress> {
    return this.http.get<SeriesProgress>(`${this.baseUrl}/watchlist/progress/${titleId}`);
  }

  /**
   * Records the last episode finished.
   *
   * Returns the new view rather than void, because recording that you watched
   * S1 E8 immediately changes what is next -- and a client that has to re-fetch
   * to find that out will render the old answer for a frame.
   */
  recordProgress(titleId: string, seasonNumber: number, episodeNumber: number): Observable<SeriesProgress> {
    return this.http.put<SeriesProgress>(`${this.baseUrl}/watchlist/progress/${titleId}`, {
      seasonNumber,
      episodeNumber,
    });
  }

  clearProgress(titleId: string): Observable<SeriesProgress> {
    return this.http.delete<SeriesProgress>(`${this.baseUrl}/watchlist/progress/${titleId}`);
  }
}

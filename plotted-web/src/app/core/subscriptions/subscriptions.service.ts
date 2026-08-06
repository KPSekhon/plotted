import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { API_BASE_URL } from '../api/api.config';
import {
  CreateSubscriptionRequest,
  Provider,
  ProviderList,
  Subscription,
  SubscriptionList,
  UpdateSubscriptionRequest,
} from './subscriptions.models';

@Injectable({ providedIn: 'root' })
export class SubscriptionsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  list(): Observable<SubscriptionList> {
    return this.http.get<SubscriptionList>(`${this.baseUrl}/subscriptions`);
  }

  /**
   * Services the user could be paying for. Carries no pricing: Plotted ships
   * none deliberately, so the price on the form starts empty and the user fills
   * it in from their own bill.
   */
  providers(): Observable<readonly Provider[]> {
    return this.http
      .get<ProviderList>(`${this.baseUrl}/providers`)
      .pipe(map((response) => response.providers));
  }

  add(request: CreateSubscriptionRequest): Observable<Subscription> {
    return this.http.post<Subscription>(`${this.baseUrl}/subscriptions`, request);
  }

  update(subscriptionId: string, request: UpdateSubscriptionRequest): Observable<Subscription> {
    return this.http.patch<Subscription>(
      `${this.baseUrl}/subscriptions/${subscriptionId}`,
      request,
    );
  }

  remove(subscriptionId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/subscriptions/${subscriptionId}`);
  }
}

import { Component, HostListener, computed, inject } from '@angular/core';
import { MatMenuModule } from '@angular/material/menu';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { PlottedXComponent } from './shared/map/plotted-x.component';

/** The three screens that answer "what should I pay for?". */
const SUBSCRIPTION_SECTION = ['/subscriptions', '/coverage', '/plan'];

@Component({
  selector: 'plotted-root',
  standalone: true,
  // Material is here only for the account menu, which is a popup with real
  // keyboard and focus-trap requirements. The header itself stays plain markup
  // so it reads as a hairline rather than a toolbar announcing itself on every
  // screen.
  imports: [RouterOutlet, RouterLink, RouterLinkActive, PlottedXComponent, MatMenuModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  protected readonly initial = computed(() => {
    const name = this.auth.user()?.displayName?.trim();
    return name ? name.charAt(0).toUpperCase() : '?';
  });

  /**
   * Whether the current URL is anywhere in the subscriptions area.
   *
   * The three screens keep their original top-level paths — moving them under
   * `/subscriptions/*` would break every link already pointing at them from
   * empty states, the home waypoints and the planner's own cross-references,
   * for a URL shape nobody sees. The grouping is in the navigation, which is
   * where the user experiences it.
   */
  protected inSubscriptions(): boolean {
    const url = this.router.url.split('?')[0];
    return SUBSCRIPTION_SECTION.some((path) => url === path || url.startsWith(`${path}/`));
  }

  protected goToSearch(): void {
    void this.router.navigate(['/search']);
  }

  /**
   * Slash focuses search, the way it does in most tools that have one.
   *
   * Ignored while the user is typing, or the shortcut would eat a literal
   * slash out of a password, a title search or a note.
   */
  @HostListener('document:keydown./', ['$event'])
  protected onSlash(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    const tag = target?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) {
      return;
    }
    if (!this.auth.isAuthenticated()) return;

    event.preventDefault();
    this.goToSearch();
  }

  protected signOut(): void {
    this.auth.logout().subscribe(() => {
      void this.router.navigate(['/sign-in']);
    });
  }
}

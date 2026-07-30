import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';

@Component({
  selector: 'plotted-root',
  standalone: true,
  // No Material here: the shell is plain markup, so the header is a hairline
  // rather than a toolbar that announces itself on every screen.
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  protected signOut(): void {
    this.auth.logout().subscribe(() => {
      void this.router.navigate(['/sign-in']);
    });
  }
}

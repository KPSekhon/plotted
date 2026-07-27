import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'plotted-not-found',
  standalone: true,
  imports: [RouterLink, MatButtonModule],
  template: `
    <section class="not-found">
      <h1>No plot here</h1>
      <p>That page does not exist.</p>
      <a mat-flat-button color="primary" routerLink="/">Back to Plotted</a>
    </section>
  `,
  styles: `
    .not-found {
      text-align: center;
      padding: 4rem 1rem;
    }
  `,
})
export class NotFoundPage {}

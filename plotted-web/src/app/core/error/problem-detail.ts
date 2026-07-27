import { HttpErrorResponse } from '@angular/common/http';

/**
 * RFC 9457 Problem Details, as returned by every Plotted endpoint.
 *
 * `code` is the stable field to branch on. `detail` is written for people and is
 * free to change, so nothing should ever match on its text.
 */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: string;
  timestamp?: string;
  errors?: Record<string, string>;
}

export function isProblemDetail(value: unknown): value is ProblemDetail {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    'status' in value
  );
}

export function problemFrom(error: unknown): ProblemDetail | null {
  if (error instanceof HttpErrorResponse && isProblemDetail(error.error)) {
    return error.error;
  }
  return null;
}

/**
 * A message worth showing. Field-level validation errors are folded in, because
 * "one or more fields are invalid" on its own tells the user nothing.
 */
export function messageFrom(error: unknown, fallback = 'Something went wrong.'): string {
  const problem = problemFrom(error);
  if (!problem) {
    return error instanceof HttpErrorResponse && error.status === 0
      ? 'Could not reach the server.'
      : fallback;
  }
  if (problem.errors && Object.keys(problem.errors).length > 0) {
    return Object.entries(problem.errors)
      .map(([field, message]) => `${field} ${message}`)
      .join('; ');
  }
  return problem.detail || problem.title;
}

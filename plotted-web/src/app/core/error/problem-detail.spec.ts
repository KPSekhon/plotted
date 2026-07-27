import { HttpErrorResponse } from '@angular/common/http';

import { messageFrom, problemFrom } from './problem-detail';

describe('problem-detail', () => {
  const problem = {
    type: 'https://plotted.app/errors/validation-failed',
    title: 'Request validation failed',
    status: 400,
    detail: 'One or more fields are invalid',
    code: 'VALIDATION_FAILED',
    errors: { password: 'must be between 12 and 200 characters' },
  };

  it('reads a Problem Detail out of an HttpErrorResponse', () => {
    const error = new HttpErrorResponse({ status: 400, error: problem });

    expect(problemFrom(error)?.code).toBe('VALIDATION_FAILED');
  });

  it('returns null for anything that is not a Problem Detail', () => {
    expect(problemFrom(new HttpErrorResponse({ status: 500, error: 'boom' }))).toBeNull();
    expect(problemFrom(new Error('boom'))).toBeNull();
  });

  it('prefers field errors over the generic detail, because the generic one says nothing', () => {
    const error = new HttpErrorResponse({ status: 400, error: problem });

    expect(messageFrom(error)).toBe('password must be between 12 and 200 characters');
  });

  it('falls back to the detail when there are no field errors', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { ...problem, errors: undefined, detail: 'An account already exists' },
    });

    expect(messageFrom(error)).toBe('An account already exists');
  });

  it('distinguishes an unreachable server from a server that answered', () => {
    const offline = new HttpErrorResponse({ status: 0 });

    expect(messageFrom(offline)).toBe('Could not reach the server.');
  });
});

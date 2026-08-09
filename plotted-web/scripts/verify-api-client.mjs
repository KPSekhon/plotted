#!/usr/bin/env node
/**
 * Checks that every endpoint the Angular application calls exists in the
 * committed OpenAPI document, with the method it is called with.
 *
 * ## Why this exists
 *
 * ADR 0005 chose a generated TypeScript client over consumer-driven contracts.
 * The generator is wired up (`npm run generate:api`) but nothing in the
 * application imports what it produces: every request model in `src/app/core`
 * is hand-written. So the ADR's guarantee -- that client types are derived from
 * the server rather than restated by hand -- does not currently hold, and
 * `OpenApiContractTest` protects only the server side of the seam. A backend
 * rename lands green, and the failure appears at runtime as a 404 on a screen
 * nobody opened during review.
 *
 * ## What this verifies, and what it does not
 *
 * Paths and methods. Nothing else.
 *
 * It does not check request or response *shapes*, and it must not be read as
 * doing so -- a field that changed type, or a response property that quietly
 * disappeared, passes here. That is a smaller claim than the name `verify:api`
 * suggests, which is exactly why it is written down: a check that overstates
 * itself is worse than no check, because the next person stops looking.
 *
 * Closing the shape gap means adopting the generated client for real. Until
 * that happens this catches the drift that is both most likely and most silent.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(scriptDir, '..');
const repoRoot = resolve(webRoot, '..');
const documentPath = join(repoRoot, 'openapi', 'openapi.json');
const sourceRoot = join(webRoot, 'src', 'app');
const apiConfigPath = join(sourceRoot, 'core', 'api', 'api.config.ts');

/**
 * Angular's `HttpClient` call sites, e.g.
 *
 *   this.http.patch<Subscription>(`${this.baseUrl}/subscriptions/${id}`, body)
 *
 * The generic is optional and the call may wrap across lines, which is why the
 * pattern tolerates whitespace rather than assuming one call fits on one line.
 */
const CALL_PATTERN =
  /\bhttp\s*\.\s*(get|post|put|patch|delete)\s*(?:<[^<>]*>)?\s*\(\s*`([^`]*)`/g;

/** `${anything}` -- a path parameter on the TypeScript side. */
const TS_INTERPOLATION = /\$\{[^}]*\}/g;

/** `{anything}` -- a path parameter on the OpenAPI side. */
const OPENAPI_PARAMETER = /\{[^}]*\}/g;

const PARAMETER = '{}';

function fail(message) {
  console.error(`verify:api: ${message}`);
  process.exit(1);
}

/**
 * The base path is read from the injection token rather than hard-coded, so a
 * change to the prefix moves this check with it instead of silently invalidating
 * every comparison below.
 */
function readBaseUrl() {
  let source;
  try {
    source = readFileSync(apiConfigPath, 'utf8');
  } catch {
    fail(`could not read ${relative(repoRoot, apiConfigPath)}`);
  }
  const match = source.match(/factory:\s*\(\)\s*=>\s*['"`]([^'"`]+)['"`]/);
  if (!match) {
    fail(
      `could not find the API_BASE_URL factory in ${relative(repoRoot, apiConfigPath)}. ` +
        'If the token stopped having a literal default, this script needs updating rather than deleting.',
    );
  }
  return match[1];
}

function typescriptFiles(directory) {
  const found = [];
  for (const entry of readdirSync(directory)) {
    const full = join(directory, entry);
    if (statSync(full).isDirectory()) {
      found.push(...typescriptFiles(full));
    } else if (entry.endsWith('.ts') && !entry.endsWith('.spec.ts')) {
      found.push(full);
    }
  }
  return found;
}

function collectCalls(baseUrl) {
  const calls = [];
  for (const file of typescriptFiles(sourceRoot)) {
    const source = readFileSync(file, 'utf8');
    const lineStarts = [...source].reduce(
      (starts, character, index) => (character === '\n' ? [...starts, index + 1] : starts),
      [0],
    );
    for (const match of source.matchAll(CALL_PATTERN)) {
      const line = lineStarts.filter((start) => start <= match.index).length;
      calls.push({
        method: match[1].toUpperCase(),
        template: match[2],
        file: relative(repoRoot, file).replaceAll('\\', '/'),
        line,
        baseUrl,
      });
    }
  }
  return calls;
}

/**
 * Requests are expected to go through the injected base URL. One that does not
 * has hard-coded the prefix, which is how a path escapes both this check and the
 * dev-server proxy at the same time.
 */
function normalise(call) {
  if (!call.template.startsWith('${this.baseUrl}')) {
    return { error: 'does not build its URL from the injected API_BASE_URL' };
  }
  const path = call.baseUrl + call.template.slice('${this.baseUrl}'.length);
  return { path: path.replaceAll(TS_INTERPOLATION, PARAMETER) };
}

function main() {
  let document;
  try {
    document = JSON.parse(readFileSync(documentPath, 'utf8'));
  } catch {
    fail(
      `could not read ${relative(repoRoot, documentPath)}. ` +
        'Run `make openapi` (needs Docker) or take it from a CI run artifact.',
    );
  }

  const documented = new Map();
  for (const [path, operations] of Object.entries(document.paths ?? {})) {
    const key = path.replaceAll(OPENAPI_PARAMETER, PARAMETER);
    const methods = documented.get(key) ?? new Set();
    for (const method of Object.keys(operations)) {
      methods.add(method.toUpperCase());
    }
    documented.set(key, methods);
  }

  const baseUrl = readBaseUrl();
  const calls = collectCalls(baseUrl);
  if (calls.length === 0) {
    fail(
      `found no HttpClient calls under ${relative(repoRoot, sourceRoot).replaceAll('\\', '/')}. ` +
        'That is far more likely to mean this script stopped matching them than that the application stopped making them.',
    );
  }

  const problems = [];
  const called = new Set();

  for (const call of calls) {
    const { path, error } = normalise(call);
    if (error) {
      problems.push(`${call.file}:${call.line}  ${call.method} \`${call.template}\` -- ${error}`);
      continue;
    }
    const methods = documented.get(path);
    if (!methods) {
      problems.push(`${call.file}:${call.line}  ${call.method} ${path} -- no such path in the API`);
    } else if (!methods.has(call.method)) {
      problems.push(
        `${call.file}:${call.line}  ${call.method} ${path} -- path exists, but only ` +
          `${[...methods].sort().join(', ')}`,
      );
    } else {
      called.add(`${call.method} ${path}`);
    }
  }

  if (problems.length > 0) {
    console.error(
      `verify:api: ${problems.length} call${problems.length === 1 ? '' : 's'} ` +
        'in plotted-web do not match openapi/openapi.json:\n',
    );
    for (const problem of problems) {
      console.error(`  ${problem}`);
    }
    console.error(
      '\nIf the API changed on purpose, regenerate the document with `make openapi` and update the caller.\n',
    );
    process.exit(1);
  }

  // Reported, never failed. An endpoint with no caller may be waiting for a
  // screen, or may be dead -- this script cannot tell which, and guessing would
  // make it fail for a reason it cannot defend.
  const uncalled = [];
  for (const [path, methods] of documented) {
    for (const method of methods) {
      if (!called.has(`${method} ${path}`)) uncalled.push(`${method} ${path}`);
    }
  }

  console.log(
    `verify:api: ${calls.length} calls match openapi/openapi.json (paths and methods only -- shapes are not checked).`,
  );
  if (uncalled.length > 0) {
    console.log(`\n${uncalled.length} documented operations have no caller in plotted-web:`);
    for (const operation of uncalled.sort()) {
      console.log(`  ${operation}`);
    }
  }
}

main();

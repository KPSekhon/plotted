#!/usr/bin/env node
/**
 * Regenerates `openapi/openapi.json` from a locally running API.
 *
 * ## Why this exists
 *
 * `OpenApiContractTest` is the authority on this file, and it is gated on
 * Docker. On a machine without Docker the only way to regenerate after an
 * intentional API change was to push, let CI fail the drift check, and download
 * `build/openapi-actual.json` from the run's artifacts — a full round trip
 * through CI for every field added.
 *
 * The document is just `/v3/api-docs` written out with Jackson's
 * `writerWithDefaultPrettyPrinter`, and the API runs fine here against native
 * Postgres. So this fetches the same endpoint and reproduces that formatter.
 *
 * ## Why the formatting is reimplemented rather than approximated
 *
 * Jackson's `DefaultPrettyPrinter` is not `JSON.stringify`. Objects break across
 * lines with `"key" : value` — spaces either side of the colon — while arrays
 * use a fixed single space, so they stay on one line and any objects inside them
 * break normally. `JSON.stringify` matches neither, and a document that differs
 * only in whitespace fails the drift check exactly as loudly as a real change.
 *
 * ## The check that makes this trustworthy
 *
 * `--verify` re-renders the committed document from its own parsed contents and
 * diffs the result. If the formatter were wrong the output would differ, and
 * this would be a tool that quietly rewrote the file into a shape CI rejects.
 * Run it before trusting a regeneration; `npm test`-style confidence in a
 * formatter is not something to assume.
 *
 *   node tools/openapi/regenerate.mjs --verify      # formatter reproduces the committed file
 *   node tools/openapi/regenerate.mjs               # rewrite from http://localhost:8080
 *
 * CI remains the authority. This shortens the loop; it does not replace the test.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const documentPath = join(repoRoot, 'openapi', 'openapi.json');
const source = process.env.PLOTTED_API_URL ?? 'http://localhost:8080';

/**
 * A number carrying how it was written, because two separate normalisations sit
 * between springdoc and the committed file and neither is `JSON.parse`.
 *
 * `JSON.parse` turns `0.0` into the number `0`, which re-renders as `0` — a
 * whitespace-level difference the drift check treats exactly as seriously as a
 * renamed field. That is the first trap, and `--verify` caught it immediately.
 *
 * The second is subtler. `OpenApiContractTest` does not write the server's bytes
 * out; it reads them with `objectMapper.readTree` first, and Jackson parses
 * floating-point literals into `DoubleNode` unless told otherwise. So the live
 * API's `"maximum" : 10000.00` — springdoc echoing the scale of the
 * `@DecimalMax("10000.00")` annotation — reaches the file as `10000.0`. Copying
 * the server's text verbatim would produce a document CI rejects, while looking
 * more faithful than the thing it disagreed with.
 *
 * So: integers keep their text, and anything with a fraction or exponent goes
 * through a double and comes back in Java's `Double.toString` form.
 */
class RawNumber {
  constructor(text) {
    this.text = text;
  }

  /** Java renders every double with a fractional part; JavaScript does not. */
  get jacksonText() {
    if (!/[.eE]/.test(this.text)) return this.text;
    const value = Number(this.text);
    return Number.isInteger(value) && Math.abs(value) < 1e7 ? `${value}.0` : `${value}`;
  }
}

/**
 * Minimal JSON reader that differs from `JSON.parse` in exactly one way: number
 * literals are preserved as written rather than converted to doubles. Everything
 * else follows RFC 8259.
 */
function parsePreservingNumbers(text) {
  let at = 0;

  const error = (message) => {
    throw new SyntaxError(`${message} at offset ${at}`);
  };
  const skipWhitespace = () => {
    while (at < text.length && ' \t\n\r'.includes(text[at])) at++;
  };
  const expect = (character) => {
    if (text[at] !== character) error(`expected ${character}`);
    at++;
  };

  const readString = () => {
    const start = at;
    expect('"');
    while (at < text.length && text[at] !== '"') at += text[at] === '\\' ? 2 : 1;
    expect('"');
    return JSON.parse(text.slice(start, at));
  };

  const readValue = () => {
    skipWhitespace();
    const character = text[at];

    if (character === '{') {
      at++;
      const object = {};
      skipWhitespace();
      if (text[at] === '}') return at++, object;
      for (;;) {
        skipWhitespace();
        const key = readString();
        skipWhitespace();
        expect(':');
        object[key] = readValue();
        skipWhitespace();
        if (text[at] === ',') {
          at++;
          continue;
        }
        expect('}');
        return object;
      }
    }

    if (character === '[') {
      at++;
      const array = [];
      skipWhitespace();
      if (text[at] === ']') return at++, array;
      for (;;) {
        array.push(readValue());
        skipWhitespace();
        if (text[at] === ',') {
          at++;
          continue;
        }
        expect(']');
        return array;
      }
    }

    if (character === '"') return readString();

    for (const literal of ['true', 'false', 'null']) {
      if (text.startsWith(literal, at)) {
        at += literal.length;
        return literal === 'null' ? null : literal === 'true';
      }
    }

    const start = at;
    while (at < text.length && !',}] \t\n\r'.includes(text[at])) at++;
    if (at === start) error('expected a value');
    return new RawNumber(text.slice(start, at));
  };

  const value = readValue();
  skipWhitespace();
  if (at !== text.length) error('trailing content');
  return value;
}

/**
 * Jackson `DefaultPrettyPrinter`: two-space object indentation, `" : "` between
 * key and value, and `FixedSpaceIndenter` for arrays — `[ a, b ]` on one line,
 * `[ ]` when empty, with nested objects still breaking across lines.
 */
function render(value, depth = 0) {
  if (value instanceof RawNumber) return value.jacksonText;
  const indent = '  '.repeat(depth + 1);
  const closeIndent = '  '.repeat(depth);

  if (Array.isArray(value)) {
    if (value.length === 0) return '[ ]';
    return `[ ${value.map((item) => render(item, depth)).join(', ')} ]`;
  }
  if (value !== null && typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length === 0) return '{ }';
    const entries = keys.map((key) => `${indent}${JSON.stringify(key)} : ${render(value[key], depth + 1)}`);
    return `{\n${entries.join(',\n')}\n${closeIndent}}`;
  }
  return JSON.stringify(value);
}

const serialise = (document) => `${render(document)}\n`;

if (process.argv.includes('--verify')) {
  const committed = readFileSync(documentPath, 'utf8').replace(/\r\n/g, '\n');
  const reRendered = serialise(parsePreservingNumbers(committed));
  if (reRendered === committed) {
    console.log('openapi: the formatter reproduces the committed document byte for byte.');
    process.exit(0);
  }
  const committedLines = committed.split('\n');
  const renderedLines = reRendered.split('\n');
  const firstDifference = committedLines.findIndex((line, index) => line !== renderedLines[index]);
  console.error(
    'openapi: the formatter does NOT reproduce the committed document, so it must not be used to rewrite it.\n' +
      `  first difference at line ${firstDifference + 1}\n` +
      `    committed: ${JSON.stringify(committedLines[firstDifference])}\n` +
      `    rendered : ${JSON.stringify(renderedLines[firstDifference])}`,
  );
  process.exit(1);
}

const response = await fetch(`${source}/v3/api-docs`).catch(() => null);
if (!response?.ok) {
  console.error(
    `openapi: could not fetch ${source}/v3/api-docs. Start the API first:\n` +
      "  ./gradlew.bat :plotted-api:bootRun --console=plain --args='--plotted.demo.enabled=true'",
  );
  process.exit(1);
}

const document = parsePreservingNumbers(await response.text());

// The running server fills this in from the request, which under a random port
// is a different value every time. `SecurityConfig`'s counterpart in springdoc
// pins it; assert rather than rewrite, so a configuration change shows up here
// instead of being silently papered over.
const serverUrl = document.servers?.[0]?.url;
if (serverUrl !== '/') {
  console.error(`openapi: expected servers[0].url to be "/" but the API reported ${JSON.stringify(serverUrl)}.`);
  process.exit(1);
}

const before = readFileSync(documentPath, 'utf8').replace(/\r\n/g, '\n');
const after = serialise(document);
writeFileSync(documentPath, after);
console.log(
  before === after
    ? 'openapi: no change — the committed document already matches the running API.'
    : `openapi: rewrote openapi/openapi.json (${before.split('\n').length} -> ${after.split('\n').length} lines).`,
);

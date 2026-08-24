/**
 * Applies operation histories through the browser CRDT implementation and prints the resulting
 * text, so a Java test can compare the two implementations character for character.
 *
 * Invoked as:  node conformance-runner.mjs <path-to-rga.js> <path-to-input.json>
 *
 * Input JSON:  { "histories": [ [op, op, ...], [op, ...] ] }
 * Output JSON: { "texts": ["...", "..."], "pending": [0, 0] }
 *
 * Operations use the same wire format the server sends, so this exercises the exact decoding path
 * a real browser client uses rather than a test-only shape.
 */
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const [, , rgaPath, inputPath] = process.argv;
if (!rgaPath || !inputPath) {
  console.error('usage: conformance-runner.mjs <rga.js> <input.json>');
  process.exit(2);
}

const { RgaDocument } = await import(pathToFileURL(rgaPath).href);
const input = JSON.parse(readFileSync(inputPath, 'utf8'));

const texts = [];
const pending = [];

for (const history of input.histories) {
  const doc = new RgaDocument('js-replica');
  for (const op of history) {
    doc.apply(op);
  }
  texts.push(doc.text());
  pending.push(doc.pendingOperationCount());
}

console.log(JSON.stringify({ texts, pending }));

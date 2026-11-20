#!/usr/bin/env node
// Small downloader used only by tools/bootstrap-toolchain.ps1.
// Node's fetch works in this environment where curl/schannel cannot acquire
// client credentials. Streams to a .part file and renames on success.
import { createWriteStream } from 'node:fs';
import { rename, rm, stat } from 'node:fs/promises';
import { dirname } from 'node:path';
import { mkdir } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const [url, output] = process.argv.slice(2);
if (!url || !output) {
  console.error('usage: node download.mjs <url> <output>');
  process.exit(2);
}

await mkdir(dirname(output), { recursive: true });
const part = `${output}.part`;
await rm(part, { force: true });

const response = await fetch(url, { redirect: 'follow' });
if (!response.ok || !response.body) {
  throw new Error(`download failed: HTTP ${response.status} for ${url}`);
}

const total = Number(response.headers.get('content-length') ?? 0);
let written = 0;
let lastReport = 0;
const source = Readable.fromWeb(response.body);
source.on('data', (chunk) => {
  written += chunk.length;
  const now = Date.now();
  if (now - lastReport > 5000) {
    lastReport = now;
    const pct = total ? ` (${((written / total) * 100).toFixed(1)}%)` : '';
    console.log(`${output}: ${(written / 1024 / 1024).toFixed(1)} MiB${pct}`);
  }
});

await pipeline(source, createWriteStream(part));
await rename(part, output);
const info = await stat(output);
console.log(`downloaded ${output} (${(info.size / 1024 / 1024).toFixed(1)} MiB)`);

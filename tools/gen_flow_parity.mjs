#!/usr/bin/env node
// Regenerates the Kotlin parity fixtures from the REAL web flow engine.
//   node tools/gen_flow_parity.mjs [--out hodhod-core/src/test/resources/flow-parity]
// Needs the Rails tree next to this project (../chatwoot) with node_modules installed (vue, date-fns-tz).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const outArg = process.argv.indexOf('--out');
const outDir = outArg > 0 ? process.argv[outArg + 1] : path.join(here, '..', 'hodhod-core', 'src', 'test', 'resources', 'flow-parity');
fs.mkdirSync(outDir, { recursive: true });

const { runScenario } = await import('./flow-parity/runtime.mjs');
const { scenarios } = await import('./flow-parity/scenarios.mjs');
const { buildVectors } = await import('./flow-parity/vectors.mjs');

let count = 0;
for (const sc of scenarios) {
  const result = await runScenario(sc);
  fs.writeFileSync(path.join(outDir, `scenario-${sc.name}.json`), `${JSON.stringify(result, null, 1)}\n`);
  count += 1;
}
const vectors = await buildVectors();
for (const [name, data] of Object.entries(vectors)) {
  fs.writeFileSync(path.join(outDir, `vectors-${name}.json`), `${JSON.stringify(data, null, 1)}\n`);
}
console.log(`wrote ${count} scenarios + ${Object.keys(vectors).length} vector files to ${outDir}`);

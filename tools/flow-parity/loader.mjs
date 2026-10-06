// ESM loader hooks for running the web widget's shared flow code under plain node:
//  * `shared/...`, `dashboard/...`, `widget/...` aliases (vite aliases of the Rails app) -> app/javascript/<alias>
//  * extensionless relative imports -> `.js`
import { existsSync, statSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const JS_ROOT = process.env.HODHOD_WEB_JS || '/Users/nobitex/bolbol/chatwoot/app/javascript';
const ALIASES = ['shared', 'dashboard', 'widget'];

const withExt = file => {
  for (const cand of [file, `${file}.js`, path.join(file, 'index.js')]) {
    if (existsSync(cand) && statSync(cand).isFile()) return cand;
  }
  return null;
};

export async function resolve(specifier, context, next) {
  if (specifier === 'date-fns-tz') return { url: new URL('./date-fns-tz-shim.mjs', import.meta.url).href, shortCircuit: true };
  const head = specifier.split('/')[0];
  if (ALIASES.includes(head)) {
    const hit = withExt(path.join(JS_ROOT, specifier));
    if (hit) return { url: pathToFileURL(hit).href, shortCircuit: true };
  }
  if ((specifier.startsWith('./') || specifier.startsWith('../')) && context.parentURL?.startsWith('file:')) {
    const base = path.resolve(path.dirname(fileURLToPath(context.parentURL)), specifier);
    const hit = withExt(base);
    if (hit) return { url: pathToFileURL(hit).href, shortCircuit: true };
  }
  return next(specifier, context);
}

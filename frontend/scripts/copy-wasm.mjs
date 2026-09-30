/**
 * Copies the MediaPipe WASM runtime out of node_modules into public/wasm.
 *
 * These files are ~33 MB and are reproducible from the installed package, so
 * they are generated at build time rather than committed. Serving them from
 * this application (instead of a CDN) is what lets an interview run without
 * internet access.
 *
 * The model weights under public/models are a different matter: they are not in
 * node_modules and have to be downloaded, so those are committed.
 */
import { cp, mkdir, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, '..', 'node_modules', '@mediapipe', 'tasks-vision', 'wasm');
const target = join(here, '..', 'public', 'wasm');

if (!existsSync(source)) {
  console.error(`[copy-wasm] Not found: ${source}\n[copy-wasm] Run "npm install" first.`);
  process.exit(1);
}

await mkdir(target, { recursive: true });
await cp(source, target, { recursive: true });

const files = await readdir(target);
console.log(`[copy-wasm] Copied ${files.length} MediaPipe runtime files into public/wasm`);

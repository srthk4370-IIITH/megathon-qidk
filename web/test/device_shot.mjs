// reads a QR code from a screenshot of the QIDK (PNG) with the page's own decoder: jsQR -> QalamDecode
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { PNG } from 'pngjs';
import '../src/decode.js';
const sandbox = { Uint8ClampedArray, Array, Error, Math, Object }; sandbox.self = sandbox;
vm.runInNewContext(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'vendor', 'jsQR.min.js'), 'utf8'), sandbox);
const img = PNG.sync.read(readFileSync(process.argv[2]));
const hit = sandbox.jsQR(new Uint8ClampedArray(img.data.buffer, img.data.byteOffset, img.data.length), img.width, img.height, { inversionAttempts: 'attemptBoth' });
if (!hit) { console.log('NO QR FOUND'); process.exit(1); }
const r = await globalThis.QalamDecode.decode(hit.data);
console.log(JSON.stringify({ chars: hit.data.length, prefix: hit.data.slice(0, 7), ok: r.ok, crcOk: r.crcOk, report: r.report }, null, 1));

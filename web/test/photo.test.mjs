// The "scan from photo" path: every sample is drawn as a QR PNG (qrcode package, error correction M) into
// web/samples, then read back from the PNG pixels with jsQR (the decoder inlined in the page) and decoded.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import QRCode from 'qrcode';
import { PNG } from 'pngjs';
import '../src/decode.js';

// the exact file that is inlined into the page, run the way a browser runs it (as a classic script with `self`)
const sandbox = { Uint8ClampedArray, Array, Error, Math, Object }; sandbox.self = sandbox;
vm.runInNewContext(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'vendor', 'jsQR.min.js'), 'utf8'), sandbox);
const jsQR = sandbox.jsQR;
const samples = join(dirname(fileURLToPath(import.meta.url)), '..', 'samples');
const names = readdirSync(samples).filter((f) => f.endsWith('.txt')).map((f) => f.slice(0, -4)).sort();

for (const name of names) {
  const code = readFileSync(join(samples, name + '.txt'), 'utf8');
  const png = await QRCode.toBuffer(code, { errorCorrectionLevel: 'M', margin: 4, scale: 6, type: 'png' });
  writeFileSync(join(samples, name + '.png'), png);
  const img = PNG.sync.read(readFileSync(join(samples, name + '.png')));
  const hit = jsQR(new Uint8ClampedArray(img.data.buffer, img.data.byteOffset, img.data.length), img.width, img.height, { inversionAttempts: 'attemptBoth' });
  assert.ok(hit, name + ': QR found in the PNG');
  assert.equal(hit.data, code, name + ': same text');
  const r = await globalThis.QalamDecode.decode(hit.data);
  assert.ok(r.ok && r.crcOk, name + ': decodes with integrity');
  assert.deepEqual(r.report, JSON.parse(readFileSync(join(samples, name + '.json'), 'utf8')));
  console.log(`photo.test: ${name}.png ${img.width}x${img.height}px, QR version ${(Math.round(img.width / 6) - 8 - 17) / 4} -> OK`);
}

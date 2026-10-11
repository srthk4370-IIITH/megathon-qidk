// Round trip against the Kotlin encoder: every web/samples/*.txt (written by the Android unit test QrPayloadTest)
// is decoded with the SAME function the page uses, and compared with the JSON the encoder says it packed.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { deflateRawSync } from 'node:zlib';
import pako from 'pako';
import '../src/decode.js';

const { decode, crc32hex } = globalThis.QalamDecode;
const samples = join(dirname(fileURLToPath(import.meta.url)), '..', 'samples');
const names = readdirSync(samples).filter((f) => f.endsWith('.txt')).map((f) => f.slice(0, -4)).sort();
assert.deepEqual(names, ['fire_hinglish', 'long_truncated', 'water_leak']);

let checks = 0;
for (const name of names) {
  const code = readFileSync(join(samples, name + '.txt'), 'utf8');
  const expected = JSON.parse(readFileSync(join(samples, name + '.json'), 'utf8'));
  assert.ok(code.startsWith('QALAM1:') && /^[A-Za-z0-9_-]+$/.test(code.slice(7)), name + ': base64url without padding');
  assert.ok(Buffer.from(code.slice(7), 'base64url').length <= 1200, name + ': at most 1,200 bytes compressed');
  for (const opts of [{}, { forcePako: true, pako }]) {                       // native DecompressionStream, then the pako fallback
    const r = await decode(code, opts);
    assert.equal(r.ok, true, name);
    assert.equal(r.crcOk, true, name + ': integrity');
    assert.equal(r.inflater, opts.forcePako ? 'pako' : 'native');
    assert.deepEqual(r.report, expected, name + ': every field');
    assert.equal(r.report.v, 1);
    for (const k of ['id', 'ts', 'type', 'sev', 'loc', 'inj', 'per', 'eq', 'act', 'wit', 'desc', 'det', 'sop', 'tags', 'eng', 'crc']) assert.ok(k in r.report, name + ' has ' + k);
    checks++;
  }
}

// field-level checks on the well-known sample
const leak = (await decode(readFileSync(join(samples, 'water_leak.txt'), 'utf8'))).report;
assert.equal(leak.id, 'INC-20261011-0005');
assert.equal(leak.type, 'Water Leak / Flooding');
assert.equal(leak.sev, 'LOW');
assert.deepEqual(leak.at, { t: '2026-10-11T10:00:00+05:30', approx: true });
assert.deepEqual(leak.tags, ['Repeat incident: 3rd water leak here in 7 days', 'Hotspot location']);
assert.equal(leak.sop.length, 3);
assert.ok(leak.tx.includes('around 10 AM'));
// non-ASCII text survives (Hinglish sample has an em dash and curly quotes)
const fire = (await decode(readFileSync(join(samples, 'fire_hinglish.txt'), 'utf8'))).report;
assert.ok(fire.desc.includes('—') && fire.desc.includes('“fire brigade”'));
// the long sample lost its transcript and its description was cut
const long = (await decode(readFileSync(join(samples, 'long_truncated.txt'), 'utf8'))).report;
assert.ok(!('tx' in long) && long.desc.endsWith('…'));

// damage and foreign codes
const good = readFileSync(join(samples, 'water_leak.txt'), 'utf8');
assert.deepEqual(await decode('https://example.com/not-a-qalam-code'), { ok: false, error: 'not-qalam' });
assert.deepEqual(await decode(''), { ok: false, error: 'not-qalam' });
assert.deepEqual(await decode('QALAM1:'), { ok: false, error: 'corrupted' });
assert.deepEqual(await decode('QALAM1:@@@@'), { ok: false, error: 'corrupted' });
for (const opts of [{}, { forcePako: true, pako }]) assert.equal((await decode(good.slice(0, -30), opts)).ok, false, 'truncated code');
// text changed after the checksum was made: still readable, flagged
const raw = JSON.stringify(leak).replace('"sev":"LOW"', '"sev":"HIGH"');
const tampered = 'QALAM1:' + deflateRawSync(Buffer.from(raw)).toString('base64url');
const t = await decode(tampered);
assert.equal(t.ok, true); assert.equal(t.crcOk, false); assert.equal(t.report.sev, 'HIGH');
assert.equal(crc32hex('123456789'), 'cbf43926');                               // CRC32 check value
assert.equal((await decode('  ' + good + '\n')).crcOk, true);                  // surrounding whitespace is ignored

console.log(`decode.test: ${names.length} samples x 2 inflaters (${checks} decodes), damage cases: OK`);

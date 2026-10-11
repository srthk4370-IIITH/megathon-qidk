// reader.html in a real (headless) Chrome, opened from disk exactly as a user would: paste, photo, live camera,
// PDF download, history, 360 px layout, dark mode, and proof that the page makes no network request.
// Uses the Chrome installed on this machine through playwright-core (no browser download).
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';
import { inflateSync } from 'node:zlib';
import { chromium } from 'playwright-core';
import { PNG } from 'pngjs';

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, '..');
const out = join(here, 'out'); mkdirSync(out, { recursive: true });
const page0 = pathToFileURL(join(web, 'reader.html')).href;
const sample = (n) => readFileSync(join(web, 'samples', n), 'utf8');
const leak = sample('water_leak.txt');

// A camera for the headless browser: the sample QR as a one-frame 1280x720 video (YUV4MPEG2), which Chrome loops.
function writeY4m(pngPath, file) {
  const img = PNG.sync.read(readFileSync(pngPath));
  const W = 1280, H = 720, ox = (W - img.width) >> 1, oy = (H - img.height) >> 1;
  assert.ok(img.width <= W && img.height <= H);
  const y = Buffer.alloc(W * H, 235), uv = Buffer.alloc((W * H) >> 1, 128);
  for (let r = 0; r < img.height; r++) for (let c = 0; c < img.width; c++) y[(r + oy) * W + c + ox] = img.data[(r * img.width + c) * 4] < 128 ? 16 : 235;
  writeFileSync(file, Buffer.concat([Buffer.from(`YUV4MPEG2 W${W} H${H} F10:1 Ip A1:1 C420jpeg\n`), Buffer.from('FRAME\n'), y, uv]));
}
const y4m = join(out, 'camera.y4m');
writeY4m(join(web, 'samples', 'water_leak.png'), y4m);

const browser = await chromium.launch({
  channel: 'chrome', headless: true,
  args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', `--use-file-for-fake-video-capture=${y4m}`, '--allow-file-access-from-files'],
});
const requests = [], errors = [];
async function open(opts = {}, query = '') {
  const ctx = await browser.newContext({ viewport: { width: 360, height: 760 }, deviceScaleFactor: 2, acceptDownloads: true, permissions: ['camera'], ...opts });
  const page = await ctx.newPage();
  page.on('request', (r) => requests.push(r.url()));
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(page0 + query);
  return { ctx, page };
}
const text = (page, sel) => page.locator(sel).innerText();
const noOverflow = async (page, what) => assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), what + ': no horizontal scroll at 360 px');

try {
  // ---- 1. paste a code, read the report
  const { ctx, page } = await open();
  assert.equal(await page.title(), 'Qalam Reader');
  await noOverflow(page, 'start');
  assert.equal(await page.locator('#result').isVisible(), false);
  await page.screenshot({ path: join(out, '1-start-light.png'), fullPage: true });
  await page.locator('#paste summary').click();
  await page.locator('#txtCode').fill(leak);
  await page.locator('#btnRead').click();
  await page.locator('#result').waitFor({ state: 'visible' });
  assert.match(await text(page, '#integrity'), /^Integrity check passed/);
  assert.match(await text(page, '#integrity'), /Checks for transmission errors; not a tamper-proof signature\./);
  assert.ok((await page.locator('#integrity').getAttribute('class')).includes('ok'));
  const rep = await text(page, '#report');
  for (const s of ['Water Leak / Flooding', 'INC-20261011-0005', 'Low', 'near Conference Room 2', 'About 11 Oct 2026, 10:00', 'Maintenance isolated the area',
    'burst pipe under the sink', 'Leak status', 'Stopped', 'SOP next steps', 'Isolate the water supply valve feeding the leak.', 'Repeat incident: 3rd water leak here in 7 days',
    'Hotspot location', 'qwen3-4b (NPU)', 'A water leak occurred near Conference Room 2.']) assert.ok(rep.includes(s), 'report shows: ' + s);
  assert.equal(await text(page, '#report .badge'), 'Low');
  await noOverflow(page, 'report');
  await page.screenshot({ path: join(out, '2-report-light.png'), fullPage: true });

  // ---- 2. PDF download
  const [dl] = await Promise.all([page.waitForEvent('download'), page.locator('#btnPdf').click()]);
  assert.equal(dl.suggestedFilename(), 'Qalam_Incident_Water_Leak_Flooding_2026-10-11.pdf');
  const pdfPath = join(out, dl.suggestedFilename());
  await dl.saveAs(pdfPath);
  const pdf = readFileSync(pdfPath);
  assert.equal(pdf.subarray(0, 5).toString(), '%PDF-');
  // page text: content streams may be Flate-compressed
  let body = pdf.toString('latin1');
  for (const m of body.matchAll(/stream\r?\n([\s\S]*?)\r?\nendstream/g)) { try { body += inflateSync(Buffer.from(m[1], 'latin1')).toString('latin1'); } catch { /* not compressed */ } }
  assert.equal((body.match(/\/Type\s*\/Page\b/g) || []).length, 1, 'one page');
  assert.ok(/\[?0 0 595\.2\d* 841\.8\d*\]?/.test(body), 'A4');
  for (const s of ['Qalam', 'Incident report', 'Water Leak / Flooding', 'INC-20261011-0005', 'near Conference Room 2', 'SOP next steps', 'Integrity check passed',
    'Generated offline by Qalam Reader', 'Checks for transmission errors; not a tamper-proof signature.']) assert.ok(body.includes(s), 'PDF contains: ' + s);
  assert.ok(pdf.length < 60 * 1024, 'small PDF: ' + pdf.length);

  // ---- 3. a damaged code and a foreign code
  await page.locator('#btnAgain').click();
  await page.locator('#txtCode').fill(leak.slice(0, -25));
  await page.locator('#btnRead').click();
  assert.match(await text(page, '#integrity'), /^Corrupted \/ not a Qalam code/);
  assert.equal(await page.locator('#btnPdf').isVisible(), false);
  await page.locator('#txtCode').fill('https://example.com/hello');
  await page.locator('#btnRead').click();
  assert.match(await text(page, '#integrity'), /^Corrupted \/ not a Qalam code/);
  await page.screenshot({ path: join(out, '3-corrupted-light.png'), fullPage: true });

  // ---- 4. scan from photo (native BarcodeDetector if this Chrome has one, else jsQR)
  await page.locator('#filePhoto').setInputFiles(join(web, 'samples', 'fire_hinglish.png'));
  await page.locator('#report h1').filter({ hasText: 'Fire / Smoke / Explosion' }).waitFor();
  assert.match(await text(page, '#integrity'), /^Integrity check passed/);
  assert.ok((await text(page, '#report')).includes('“fire brigade”'));
  assert.equal(await text(page, '#report .badge'), 'Critical');

  // ---- 5. history survives a reload, and can be cleared
  await page.reload();
  assert.equal(await page.locator('#histList .hist').count(), 2);
  assert.ok((await text(page, '#histList')).includes('INC-20261011-0002') && (await text(page, '#histList')).includes('INC-20261011-0005'));
  await page.locator('#histList .hist').nth(1).click();
  await page.locator('#report h1').filter({ hasText: 'Water Leak' }).waitFor();
  await page.locator('#btnClear').click();
  assert.equal(await page.locator('#histList .hist').count(), 0);
  assert.equal(await page.locator('#histEmpty').isVisible(), true);
  assert.equal(await page.evaluate(() => localStorage.getItem('qalam.reader.history.v1')), null);
  await ctx.close();

  // ---- 6. photo again with jsQR forced, the dense (version 33) code, in dark mode
  const d = await open({ colorScheme: 'dark' }, '?detector=jsqr');
  await d.page.locator('#filePhoto').setInputFiles(join(web, 'samples', 'long_truncated.png'));
  await d.page.locator('#report h1').waitFor();
  assert.match(await text(d.page, '#integrity'), /^Integrity check passed/);
  assert.ok((await text(d.page, '#report')).includes('INC-20261011-0009'));
  assert.equal(await d.page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgb(15, 17, 21)');
  await noOverflow(d.page, 'dark report');
  await d.page.screenshot({ path: join(out, '4-report-dark.png'), fullPage: true });
  const [dl2] = await Promise.all([d.page.waitForEvent('download'), d.page.locator('#btnPdf').click()]);
  await dl2.saveAs(join(out, 'long-' + dl2.suggestedFilename()));
  let body2 = readFileSync(join(out, 'long-' + dl2.suggestedFilename())).toString('latin1');
  assert.equal((body2.match(/\/Type\s*\/Page\b/g) || []).length, 1, 'a very long report still fits one page');
  await d.ctx.close();

  // ---- 7. live camera (fake camera showing the sample code), with the native detector and with jsQR
  for (const q of ['', '?detector=jsqr']) {
    const c = await open({}, q);
    assert.equal(await c.page.evaluate(() => !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)), true, 'camera API available for a page opened from disk in this Chrome');
    await c.page.locator('#btnCamera').click();
    await c.page.locator('#report h1').filter({ hasText: 'Water Leak' }).waitFor({ timeout: 20000 });
    assert.match(await text(c.page, '#integrity'), /^Integrity check passed/);
    assert.equal(await c.page.locator('#camera').isVisible(), false, 'camera stops after a successful scan');
    await c.ctx.close();
  }

  // ---- 8. zero network, no script errors
  const outside = requests.filter((u) => !u.startsWith('file:') && !u.startsWith('data:') && !u.startsWith('blob:'));
  assert.deepEqual(outside, [], 'no network request of any kind');
  assert.deepEqual(errors, [], 'no page errors');
  console.log(`browser.test: paste, PDF (${pdf.length} bytes, 1 page A4), damage, photo x2, history, dark, live camera x2, ${requests.length} requests all local: OK`);
} finally {
  await browser.close();
}

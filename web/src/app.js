/* Qalam Reader page logic. Everything runs in this page; nothing is fetched. */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var HISTORY_KEY = 'qalam.reader.history.v1';
  var HISTORY_MAX = 30;
  var params = new URLSearchParams(location.search);
  var forceJsQr = params.get('detector') === 'jsqr';      // debugging aid: skip the native BarcodeDetector
  var current = null;                                      // { code, result } of the report on screen
  var stream = null, scanTimer = null, scanning = false;

  // ---------------------------------------------------------------- small helpers
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;                // textContent only: a QR code is untrusted input
    return e;
  }
  function say(text, isError) { var m = $('msg'); m.textContent = text || ''; m.className = 'small ' + (isError ? 'error' : 'muted'); }
  function list(v) { return Array.isArray(v) ? v.filter(function (x) { return x != null && String(x).trim() !== ''; }).map(String) : []; }
  function str(v) { return v == null ? '' : String(v); }
  function severity(r) { var s = str(r.sev).toUpperCase(); return ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].indexOf(s) >= 0 ? s : ''; }
  function nice(s) { return s ? s.charAt(0) + s.slice(1).toLowerCase() : 'Not set'; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }

  /** "2026-10-11T10:00:00+05:30" → "11 Oct 2026, 10:00" in the report's own offset (not this device's time zone). */
  function fmtIso(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(str(iso));
    if (!m) return str(iso);
    var mon = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][+m[2] - 1] || m[2];
    var off = /([+-]\d{2}:\d{2}|Z)$/.exec(str(iso));
    return (+m[3]) + ' ' + mon + ' ' + m[1] + ', ' + m[4] + ':' + m[5] + (off && off[1] !== '+05:30' ? ' (UTC' + (off[1] === 'Z' ? '' : off[1]) + ')' : '');
  }
  function fmtNow(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()); }

  /** The fields of a report in display order: [label, text]. Shared by the page and the PDF. */
  function fields(r) {
    var at = r.at && typeof r.at === 'object' ? (r.at.approx ? 'About ' : '') + fmtIso(r.at.t) : '';
    var rows = [
      ['Reference', str(r.id)], ['Reported', fmtIso(r.ts)], ['Type', str(r.type)], ['Severity', nice(severity(r))], ['Location', str(r.loc)],
      ['Incident time', at], ['Injuries', str(r.inj)], ['Persons', list(r.per).join('\n')], ['Equipment', list(r.eq).join(', ')],
      ['Actions taken', list(r.act).join('\n')], ['Witnesses', list(r.wit).join('\n')], ['Description', str(r.desc)],
    ];
    var det = r.det && typeof r.det === 'object' && !Array.isArray(r.det) ? r.det : {};
    Object.keys(det).forEach(function (k) { rows.push([k, str(det[k])]); });
    rows.push(['Repeat / hotspot', list(r.tags).join('\n')]);
    rows.push(['Engine', str(r.eng)]);
    return rows.filter(function (x) { return x[1].trim() !== ''; });
  }

  // ---------------------------------------------------------------- history (localStorage, never required)
  function loadHistory() { try { var v = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
  function saveHistory(h) { try { localStorage.setItem(HISTORY_KEY, JSON.stringify(h)); return true; } catch (e) { return false; } }
  function remember(code, result) {
    var r = result.report, h = loadHistory().filter(function (x) { return x && x.code !== code; });
    h.unshift({ code: code, id: str(r.id), type: str(r.type), sev: severity(r), ts: str(r.ts), ok: !!result.crcOk, seen: Date.now() });
    saveHistory(h.slice(0, HISTORY_MAX));
    drawHistory();
  }
  function drawHistory() {
    var h = loadHistory(), box = $('histList');
    box.textContent = '';
    h.forEach(function (x) {
      var b = el('button', 'hist'); b.type = 'button';
      b.appendChild(el('span', 'dot ' + str(x.sev)));
      var t = el('span'); t.appendChild(el('b', null, x.type || 'Report')); t.appendChild(el('span', 'small muted mono', (x.id || '') + (x.ok ? '' : ' · failed integrity check')));
      b.appendChild(t);
      b.addEventListener('click', function () { handle(x.code, 'history'); });
      box.appendChild(b);
    });
    box.hidden = h.length === 0; $('histEmpty').hidden = h.length !== 0; $('btnClear').hidden = h.length === 0;
  }

  // ---------------------------------------------------------------- showing a report
  function showFailure(kind) {
    current = null;
    $('result').hidden = false; $('report').textContent = ''; $('btnPdf').hidden = true;
    var b = $('integrity'); b.className = 'banner bad'; b.textContent = 'Corrupted / not a Qalam code';
    b.appendChild(el('small', null, kind === 'not-qalam' ? 'This is not a code made by Qalam’s “Share offline”.' : 'The code is incomplete or damaged. Scan it again, closer and without glare.'));
    $('result').scrollIntoView({ block: 'start' });
  }

  function showReport(code, result) {
    var r = result.report;
    current = { code: code, result: result };
    $('result').hidden = false; $('btnPdf').hidden = false;
    var b = $('integrity');
    if (result.crcOk) { b.className = 'banner ok'; b.textContent = 'Integrity check passed'; }
    else { b.className = 'banner bad'; b.textContent = 'Corrupted / not a Qalam code'; b.appendChild(el('small', null, 'The checksum does not match: some of what is shown below may be wrong. Scan the code again.')); }
    b.appendChild(el('small', null, 'Checks for transmission errors; not a tamper-proof signature.'));

    var box = $('report'); box.textContent = '';
    box.appendChild(el('h1', null, str(r.type) || 'Incident report'));
    var line = el('p'); var badge = el('span', 'badge ' + severity(r), nice(severity(r))); line.appendChild(badge);
    line.appendChild(el('span', 'mono muted', '  ' + str(r.id)));
    box.appendChild(line);
    var tags = list(r.tags);
    if (tags.length) { var tg = el('div', 'tags'); tags.forEach(function (t) { tg.appendChild(el('span', /^repeat/i.test(t) || /^emergency/i.test(t) ? 'tag' : 'tag neutral', t)); }); box.appendChild(tg); }

    box.appendChild(el('h2', null, 'Report'));
    var dl = el('dl', 'card');
    fields(r).forEach(function (f) {
      if (f[0] === 'Repeat / hotspot') return;              // shown as tags above
      var row = el('div', 'row'); row.appendChild(el('dt', null, f[0])); row.appendChild(el('dd', null, f[1])); dl.appendChild(row);
    });
    box.appendChild(dl);

    var sop = list(r.sop);
    if (sop.length) {
      box.appendChild(el('h2', null, 'SOP next steps'));
      var ol = el('ol', 'card steps'); sop.forEach(function (s) { ol.appendChild(el('li', null, s)); }); box.appendChild(ol);
    }
    if (str(r.tx).trim()) {
      var d = el('details'); d.appendChild(el('summary', null, 'Transcript, word for word'));
      var p = el('div', 'card pad'); p.style.whiteSpace = 'pre-wrap'; p.textContent = str(r.tx); d.appendChild(p); box.appendChild(d);
    }
    if (r.v !== 1) box.appendChild(el('p', 'small muted', 'This code uses payload version ' + str(r.v) + '; this reader knows version 1, so some fields may be missing.'));
    $('result').scrollIntoView({ block: 'start' });
  }

  /** Every input method ends here. */
  async function handle(text, source) {
    stopCamera();
    say('');
    var result = await QalamDecode.decode(text);
    if (!result.ok) { showFailure(result.error); return result; }
    showReport(String(text).trim(), result);
    if (source !== 'history') remember(String(text).trim(), result);
    return result;
  }

  // ---------------------------------------------------------------- reading a QR code out of pixels
  var detector = null;
  async function nativeDetector() {
    if (forceJsQr || !('BarcodeDetector' in window)) return null;
    if (detector) return detector;
    try {
      var formats = await window.BarcodeDetector.getSupportedFormats();
      if (formats.indexOf('qr_code') < 0) return null;
      detector = new window.BarcodeDetector({ formats: ['qr_code'] });
      return detector;
    } catch (e) { return null; }
  }
  var canvas = document.createElement('canvas');
  function jsqrFrom(source, sw, sh, maxSide, both) {
    var k = Math.min(1, maxSide / Math.max(sw, sh));
    var w = Math.max(1, Math.round(sw * k)), h = Math.max(1, Math.round(sh * k));
    canvas.width = w; canvas.height = h;
    var ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(source, 0, 0, w, h);
    var img = ctx.getImageData(0, 0, w, h);
    var hit = jsQR(img.data, w, h, { inversionAttempts: both ? 'attemptBoth' : 'dontInvert' });
    return hit && hit.data ? hit.data : null;
  }
  /** Text of a QR code in an image / video frame, or null. Native detector first, then jsQR. */
  async function readQr(source, sw, sh, sizes, both) {
    var d = await nativeDetector();
    if (d) { try { var codes = await d.detect(source); if (codes && codes.length && codes[0].rawValue) return codes[0].rawValue; } catch (e) { /* fall through to jsQR */ } }
    for (var i = 0; i < sizes.length; i++) {
      if (i > 0 && sizes[i] > Math.max(sw, sh) && sizes[i - 1] >= Math.max(sw, sh)) continue;   // no point enlarging twice
      var t = jsqrFrom(source, sw, sh, sizes[i], both);
      if (t) return t;
    }
    return null;
  }

  // ---------------------------------------------------------------- 1. live camera
  function stopCamera() {
    scanning = false;
    if (scanTimer) { clearTimeout(scanTimer); scanTimer = null; }
    if (stream) { stream.getTracks().forEach(function (t) { t.stop(); }); stream = null; }
    var v = $('video'); if (v) v.srcObject = null;
    $('camera').hidden = true;
  }
  async function startCamera() {
    stopCamera();
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      say('Live camera is not available here (this browser blocks it for a page opened from a file). Use “Scan from photo”: it works everywhere.', true);
      return;
    }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } } });
    } catch (e) {
      say('The camera could not be opened (' + (e && e.name ? e.name : 'blocked') + '). Use “Scan from photo” instead.', true);
      return;
    }
    var v = $('video'); v.srcObject = stream; $('camera').hidden = false;
    try { await v.play(); } catch (e) { /* autoplay refused: the user can still tap the video */ }
    say('Point the camera at the QR code. Fill the frame and avoid glare.');
    scanning = true;
    var tick = async function () {
      if (!scanning) return;
      var text = null;
      if (v.readyState >= 2 && v.videoWidth) { try { text = await readQr(v, v.videoWidth, v.videoHeight, [1280], false); } catch (e) { text = null; } }
      if (!scanning) return;
      if (text && text.indexOf(QalamDecode.PREFIX) === 0) { handle(text, 'camera'); return; }
      if (text) say('That QR code is not a Qalam code. Keep looking…');
      scanTimer = setTimeout(tick, 120);
    };
    tick();
  }

  // ---------------------------------------------------------------- 2. photo (works where the live camera is blocked)
  async function scanPhoto(file) {
    if (!file) return;
    stopCamera();
    say('Reading the photo…');
    var src = null, w = 0, h = 0, url = null;
    try {
      if (window.createImageBitmap) { try { src = await createImageBitmap(file); w = src.width; h = src.height; } catch (e) { src = null; } }
      if (!src) {
        url = URL.createObjectURL(file);
        src = await new Promise(function (ok, bad) { var i = new Image(); i.onload = function () { ok(i); }; i.onerror = bad; i.src = url; });
        w = src.naturalWidth; h = src.naturalHeight;
      }
      var text = await readQr(src, w, h, [1600, 1000, 2400, 3200, 640], true);
      if (!text) { say('No QR code was found in that photo. Take it again: closer, straight on, the whole code in view.', true); return; }
      await handle(text, 'photo');
    } catch (e) {
      say('That file could not be read as a picture.', true);
    } finally {
      if (url) URL.revokeObjectURL(url);
      if (src && src.close) src.close();
    }
  }

  // ---------------------------------------------------------------- PDF (jsPDF, one A4 page)
  /** jsPDF's built-in fonts are WinAnsi: map the typography we use, drop anything else. */
  function ansi(s) {
    return str(s).replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/[–—]/g, '-').replace(/…/g, '...')
      .replace(/≈/g, '~').replace(/·/g, '-').replace(/[←-↓]/g, '').replace(/ /g, ' ').replace(/[^\x09\x0A\x20-\x7E\xA1-\xFF]/g, '?');
  }
  function pdfName(r) {
    var type = ansi(r.type).replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 40) || 'Report';
    var date = /^\d{4}-\d{2}-\d{2}/.exec(str(r.ts)); date = date ? date[0] : fmtNow(new Date()).slice(0, 10);
    return 'Qalam_Incident_' + type + '_' + date + '.pdf';
  }
  function buildPdf(r, crcOk, scale) {
    var doc = new window.jspdf.jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait' });
    doc.setLineHeightFactor(1.32);                                         // matches lh() below, so wrapped text and the next row line up
    var W = 210, H = 297, L = 16, R = 194, y = 20;
    var pt = function (n) { return n * scale; };
    var lh = function (n) { return n * scale * 0.3528 * 1.32; };           // line height in mm for a font size in pt
    var sevColor = { LOW: [21, 128, 61], MEDIUM: [180, 83, 9], HIGH: [194, 65, 12], CRITICAL: [185, 28, 28] }[severity(r)] || [107, 114, 128];

    doc.setFont('helvetica', 'bold'); doc.setFontSize(20); doc.setTextColor(229, 72, 77); doc.text('Qalam', L, y);
    doc.setFont('helvetica', 'normal'); doc.setFontSize(10); doc.setTextColor(107, 114, 128); doc.text('Incident report', R, y, { align: 'right' });
    y += 4; doc.setDrawColor(229, 231, 235); doc.setLineWidth(0.3); doc.line(L, y, R, y); y += 9 * scale;

    doc.setFont('helvetica', 'bold'); doc.setFontSize(pt(16)); doc.setTextColor(17, 24, 39);
    var title = doc.splitTextToSize(ansi(r.type) || 'Incident report', R - L - 34);
    doc.text(title, L, y);
    var label = nice(severity(r)); doc.setFontSize(pt(10));
    var bw = doc.getTextWidth(label) + 6;
    doc.setFillColor(sevColor[0], sevColor[1], sevColor[2]); doc.roundedRect(R - bw, y - 4.6 * scale, bw, 6.4 * scale, 1.2, 1.2, 'F');
    doc.setTextColor(255, 255, 255); doc.text(label, R - bw / 2, y - 0.2 * scale, { align: 'center' });
    y += (title.length - 1) * lh(16) + 5 * scale;
    doc.setFont('courier', 'normal'); doc.setFontSize(pt(10)); doc.setTextColor(107, 114, 128); doc.text(ansi(r.id), L, y); y += 6 * scale;

    var labelW = 36;
    fields(r).forEach(function (f) {
      if (f[0] === 'Reference' || f[0] === 'Type') return;                 // already in the heading
      doc.setFont('helvetica', 'normal'); doc.setFontSize(pt(10));
      var lines = doc.splitTextToSize(ansi(f[1]), R - L - labelW);
      doc.setDrawColor(229, 231, 235); doc.setLineWidth(0.2); doc.line(L, y - 1.2 * scale, R, y - 1.2 * scale);
      y += lh(10) * 0.85;
      doc.setFontSize(pt(8.5)); doc.setTextColor(107, 114, 128); doc.text(ansi(f[0]), L, y);
      doc.setFontSize(pt(10)); doc.setTextColor(17, 24, 39); doc.text(lines, L + labelW, y);
      y += (lines.length - 1) * lh(10) + lh(10) * 0.75;
    });
    doc.setDrawColor(229, 231, 235); doc.line(L, y - 1.2 * scale, R, y - 1.2 * scale);

    var sop = list(r.sop);
    if (sop.length) {
      y += 5 * scale;
      doc.setFont('helvetica', 'bold'); doc.setFontSize(pt(11)); doc.setTextColor(17, 24, 39); doc.text('SOP next steps', L, y); y += lh(11) * 1.1;
      doc.setFont('helvetica', 'normal'); doc.setFontSize(pt(10));
      sop.forEach(function (s, i) {
        var lines = doc.splitTextToSize(ansi(s), R - L - 8);
        doc.setTextColor(107, 114, 128); doc.text((i + 1) + '.', L, y);
        doc.setTextColor(17, 24, 39); doc.text(lines, L + 8, y);
        y += lines.length * lh(10) + 0.8 * scale;
      });
    }
    y += 4 * scale;
    doc.setFont('helvetica', 'normal'); doc.setFontSize(pt(9));
    if (crcOk) { doc.setTextColor(21, 128, 61); doc.text('Integrity check passed (CRC32 ' + ansi(r.crc) + ').', L, y); }
    else { doc.setTextColor(185, 28, 28); doc.text('Integrity check FAILED: the code was damaged in transit; values may be wrong.', L, y); }
    y += lh(9);
    doc.setTextColor(107, 114, 128); doc.text('Checks for transmission errors; not a tamper-proof signature.', L, y);

    doc.setDrawColor(229, 231, 235); doc.setLineWidth(0.3); doc.line(L, H - 16, R, H - 16);
    doc.setFontSize(9); doc.setTextColor(107, 114, 128);
    doc.text('Generated offline by Qalam Reader', L, H - 11);
    doc.text('Generated ' + fmtNow(new Date()), R, H - 11, { align: 'right' });
    return { doc: doc, bottom: y, limit: H - 20 };
  }
  /** One page: the text is made a little smaller until it fits; a very long description is cut last. */
  function makePdf(report, crcOk) {
    var r = report, out = null;
    for (var pass = 0; pass < 6; pass++) {
      var scales = [1, 0.92, 0.85, 0.78, 0.72, 0.66];
      for (var i = 0; i < scales.length; i++) { out = buildPdf(r, crcOk, scales[i]); if (out.bottom <= out.limit) return out.doc; }
      var d = str(r.desc); if (d.length < 200) break;
      r = Object.assign({}, r, { desc: d.slice(0, Math.floor(d.length * 0.6)).replace(/\s+\S*$/, '') + '...' });
    }
    return out.doc;
  }
  function downloadPdf() {
    if (!current) return;
    try {
      var r = current.result.report;
      makePdf(r, current.result.crcOk).save(pdfName(r));
      say('PDF saved: ' + pdfName(r));
    } catch (e) { say('The PDF could not be made on this browser.', true); }
  }

  // ---------------------------------------------------------------- wiring
  $('btnCamera').addEventListener('click', startCamera);
  $('btnStop').addEventListener('click', function () { stopCamera(); say(''); });
  $('filePhoto').addEventListener('change', function (e) { var f = e.target.files && e.target.files[0]; scanPhoto(f); e.target.value = ''; });
  $('lblPhoto').addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('filePhoto').click(); } });
  $('btnRead').addEventListener('click', function () {
    var t = $('txtCode').value.trim();
    if (!t) { say('Paste a code that starts with QALAM1: first.', true); return; }
    handle(t, 'paste');
  });
  $('btnPdf').addEventListener('click', downloadPdf);
  $('btnAgain').addEventListener('click', function () { $('result').hidden = true; current = null; say(''); window.scrollTo(0, 0); });
  $('btnClear').addEventListener('click', function () { try { localStorage.removeItem(HISTORY_KEY); } catch (e) { /* storage unavailable */ } drawHistory(); });
  document.addEventListener('visibilitychange', function () { if (document.hidden) stopCamera(); });
  drawHistory();

  // Served over https (optional): cache the page so it also opens with no network next time. From a file this is skipped.
  if (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1') {
    var link = document.createElement('link'); link.rel = 'manifest'; link.href = 'manifest.json'; document.head.appendChild(link);
    if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(function () { /* not essential */ });
  }

  window.QalamReader = { handle: handle, fields: fields, pdfName: pdfName, makePdf: makePdf };
})();

/*
 * Qalam QR payload decoder. The same file is inlined into reader.html and imported by the Node tests.
 *
 * Payload:  "QALAM1:" + base64url (no padding) of raw DEFLATE (no zlib header) of compact UTF-8 JSON.
 * The JSON ends with "crc":"xxxxxxxx"} where xxxxxxxx is the CRC32 (lower-case hex) of the same text with
 * that tail replaced by "crc":""}. The check is done on the received text; nothing is re-serialised.
 */
(function (g) {
  'use strict';
  var PREFIX = 'QALAM1:';
  var MAX_JSON_BYTES = 1 << 20;

  var TABLE = (function () {
    var t = new Uint32Array(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(bytes) {
    var c = 0xFFFFFFFF;
    for (var i = 0; i < bytes.length; i++) c = TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  function crc32hex(text) {
    return ('00000000' + crc32(new TextEncoder().encode(text)).toString(16)).slice(-8);
  }

  function base64urlToBytes(s) {
    if (!/^[A-Za-z0-9_-]+$/.test(s)) throw new Error('bad base64url');
    var b64 = s.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4) b64 += '=';
    var bin = atob(b64);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  /** Native DecompressionStream('deflate-raw') when the browser has it, otherwise pako (opts.pako or the global). */
  async function inflateRaw(bytes, opts) {
    opts = opts || {};
    if (!opts.forcePako && typeof DecompressionStream === 'function') {
      var stream = null;
      try { stream = new DecompressionStream('deflate-raw'); } catch (e) { stream = null; }   // format not supported here
      if (stream) {
        var buf = await new Response(new Blob([bytes]).stream().pipeThrough(stream)).arrayBuffer();
        return { bytes: new Uint8Array(buf), inflater: 'native' };
      }
    }
    var p = opts.pako || g.pako;
    if (!p) throw new Error('no inflate available');
    var out = p.inflateRaw(bytes);
    if (!out) throw new Error('inflate failed');
    return { bytes: out, inflater: 'pako' };
  }

  /**
   * Never throws. Result:
   *   { ok: true,  crcOk, report, json, inflater, version }   a Qalam code that could be read (crcOk false = damaged in transit)
   *   { ok: false, error: 'not-qalam' | 'corrupted' }        not a Qalam code, or too damaged to read at all
   */
  async function decode(text, opts) {
    var t = String(text == null ? '' : text).trim();
    if (t.indexOf(PREFIX) !== 0) return { ok: false, error: 'not-qalam' };
    try {
      var packed = base64urlToBytes(t.slice(PREFIX.length));
      var inflated = await inflateRaw(packed, opts);
      if (inflated.bytes.length > MAX_JSON_BYTES) return { ok: false, error: 'corrupted' };
      var json = new TextDecoder('utf-8', { fatal: true }).decode(inflated.bytes);
      var report = JSON.parse(json);
      if (!report || typeof report !== 'object' || Array.isArray(report)) return { ok: false, error: 'corrupted' };
      var m = /"crc":"([0-9a-f]{8})"\}$/.exec(json);
      var crcOk = !!m && crc32hex(json.slice(0, m.index) + '"crc":""}') === m[1];
      return { ok: true, crcOk: crcOk, report: report, json: json, inflater: inflated.inflater, version: report.v };
    } catch (e) {
      return { ok: false, error: 'corrupted' };
    }
  }

  g.QalamDecode = { PREFIX: PREFIX, decode: decode, crc32: crc32, crc32hex: crc32hex, base64urlToBytes: base64urlToBytes };
})(typeof globalThis !== 'undefined' ? globalThis : (typeof self !== 'undefined' ? self : this));

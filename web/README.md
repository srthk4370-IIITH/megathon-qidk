# Qalam Reader

`reader.html` is one self-contained web page that reads the QR code shown by Qalam's **Share offline** button and
shows the incident report, checks its integrity and makes a one-page PDF. It needs no network at any point: there
is no CDN link, no external font and no server. The QR decoder, the PDF writer, the inflate fallback and the font
are all inside the file (about 560 KB).

It exists so that a report can leave the QIDK without Wi-Fi, Bluetooth pairing or a cable: the board shows a QR
code, any phone reads it.

## Use it offline

1. Copy `reader.html` to the phone or laptop (AirDrop, USB, a chat attachment, or download it once).
2. Open the file in a browser (Chrome, Edge, Firefox, Safari). No installation.
3. In Qalam, open a report and tap **Share offline**. A full-screen QR code appears.
4. In the reader choose one of:
   - **Scan with camera**: live scan with the back camera.
   - **Scan from photo**: take a picture of the code (or pick one). Works where the live camera is blocked.
   - **Paste code**: paste the `QALAM1:…` text (for testing).
5. The report appears with a green **Integrity check passed** or a red **Corrupted / not a Qalam code**.
6. **Download PDF** saves `Qalam_Incident_<type>_<date>.pdf` (one A4 page).

Scanned reports are listed under "Scanned in this browser" (kept in this browser's `localStorage` only; **Clear**
removes them).

Optional: when the folder is served over https, `manifest.json` and `sw.js` (cache-first) make the page installable
and keep it available after the first visit. The single file does not need them.

## Payload format (the contract with the app)

```
QALAM1:<base64url, no padding>
        └─ raw DEFLATE (no zlib header)
            └─ compact UTF-8 JSON
```

App side: `java.util.zip.Deflater(9, true)`, `Base64.getUrlEncoder().withoutPadding()`
(`android/domain/.../core/QrPayload.kt`). Reader side: `DecompressionStream('deflate-raw')`, with pako as the
fallback (`src/decode.js`).

JSON, schema version 1. Values are already readable text (labels, not codes), so the reader needs no taxonomy.

| Key | Meaning |
|---|---|
| `v` | schema version, `1` |
| `id` | reference number, e.g. `INC-20261011-0005` |
| `ts` | when the report was created, ISO-8601 with offset |
| `type` | incident type label; several types joined with ` + ` |
| `sev` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` or empty |
| `loc` | location as said, with the site-registry place in brackets when it is known |
| `at` | incident time: `{"t": "<ISO-8601 with offset>", "approx": true|false}`; absent when unknown |
| `inj` | injuries, e.g. `No`, `Yes, 1 person`, `Unknown` |
| `per` | persons, array of strings |
| `eq` | equipment, array of strings |
| `act` | actions taken, array of strings |
| `wit` | witnesses, array of strings |
| `desc` | description |
| `det` | category-specific details, object of label → value |
| `sop` | SOP next steps, array of strings in order |
| `tags` | repeat / hotspot / emergency information, array of sentences |
| `eng` | engine / model that filled the form |
| `tx` | transcript (optional) |
| `crc` | CRC32, 8 lower-case hex digits; always the last key |

**Checksum.** The JSON text ends with `"crc":"xxxxxxxx"}`. `xxxxxxxx` is the CRC32 of the UTF-8 bytes of the same
text with that tail replaced by `"crc":""}`. The reader checks it on the text it received and never re-serialises,
so key order and escaping cannot cause a false alarm. It detects transmission errors. It is **not** a signature:
anyone can make a valid code.

**Size.** The app keeps the compressed payload at or under 1,200 bytes (about 1,607 characters, a version 33 QR
code at error-correction level M). If a report is bigger it drops `tx` first, then shortens `desc` (ending in `…`);
only if that is still not enough it keeps the first three `sop` steps and drops `det`.

The reporter's name and employee id are not put in the code.

## Build and test

`reader.html` is generated; edit `src/` and rebuild.

```
npm install          # dev tools only (esbuild, qrcode, pngjs, playwright-core) and the three libraries
node build.mjs       # src/ + vendor/ -> reader.html   (add --vendor to refresh vendor/ from node_modules)
npm test
```

| Test | What it proves |
|---|---|
| `test/decode.test.mjs` | Every `samples/*.txt` (written by the Android unit test `QrPayloadTest`) decodes with the page's own decode function to exactly the JSON the Kotlin encoder packed; native inflate and pako; damaged and foreign codes. |
| `test/photo.test.mjs` | Each sample is drawn as a QR PNG (`samples/*.png`, level M) and read back from the pixels with the bundled jsQR. |
| `test/browser.test.mjs` | `reader.html` opened from disk in headless Chrome: paste, report fields, PDF download (file name, one A4 page, text), corrupted code, photo input, history and Clear, 360 px layout, dark mode, live camera (a fake camera playing the sample code), and zero network requests. |

Needs Node 18 or newer; the browser test uses the Chrome installed on the machine.

Libraries and licences: `vendor/LICENSES.md`.

## Limitations

- **Live camera from a file.** Browsers decide whether a page opened from disk may use the camera. Desktop Chrome
  allows it; some mobile browsers do not. "Scan from photo" always works and gives the same result.
- **iPhone.** Not tried on an iPhone. The Files app may show an HTML file as a preview that does not run scripts;
  if so, open it in a browser that loads local pages, or serve the folder over https once (it then works offline
  through `sw.js`).
- **Not tamper-proof.** CRC32 catches a misread code, not a forged one.
- **One QR code per report.** Very long reports lose the transcript and part of the description (the page says
  so with `…`); the full report stays on the device that created it.
- **PDF text is Latin script.** The PDF uses the standard Helvetica font, so English and romanised Hinglish print
  correctly; characters outside Latin-1 (for example Devanagari) are shown on the page but replaced by `?` in the PDF.
- **History is per browser.** It lives in `localStorage`, which a private window or a browser that blocks storage
  for local files will not keep; the reader still works.
- **Dense codes need a sharp picture.** Fill the frame, hold the phone straight and avoid glare on the screen.

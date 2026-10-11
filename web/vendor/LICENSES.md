# Libraries bundled into reader.html

Copied from npm and inlined by `web/build.mjs`. Nothing is loaded at run time.

| File | Package | Licence |
|---|---|---|
| `jsQR.min.js` | jsQR 1.4.0 (minified with esbuild) | Apache-2.0 |
| `jspdf.umd.min.js` | jsPDF 2.5.2 | MIT |
| `pako_inflate.min.js` | pako 2.1.0, inflate only | MIT and Zlib |
| `inter-latin.woff2` | Inter 4.001, Latin subset, weights 400 to 700 | SIL Open Font License 1.1 |

The PDF uses the standard Helvetica and Courier fonts built into every PDF viewer; no font is embedded in it.

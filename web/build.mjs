// Builds web/reader.html: ONE self-contained file (template + font + vendored libraries + our two scripts).
//   node build.mjs            build from web/vendor (needs nothing but Node)
//   node build.mjs --vendor   first refresh web/vendor from node_modules (after `npm install`)
import { readFileSync, writeFileSync, existsSync, copyFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const p = (...a) => join(here, ...a);

if (process.argv.includes('--vendor')) {
  const esbuild = await import('esbuild');
  const jsqr = readFileSync(p('node_modules/jsqr/dist/jsQR.js'), 'utf8');
  writeFileSync(p('vendor/jsQR.min.js'), (await esbuild.transform(jsqr, { minify: true, legalComments: 'none' })).code);
  copyFileSync(p('node_modules/jspdf/dist/jspdf.umd.min.js'), p('vendor/jspdf.umd.min.js'));
  copyFileSync(p('node_modules/pako/dist/pako_inflate.min.js'), p('vendor/pako_inflate.min.js'));
  console.log('vendor/ refreshed from node_modules');
}

const inline = (js) => js.replace(/<\/script/gi, '<\\/script').replace(/<!--/g, '<\\!--').replace(/\/\/# sourceMappingURL=.*$/gm, '');
const script = (name, js) => `<script>/* ${name} */\n${inline(js)}\n</script>`;

let font = '';
if (existsSync(p('vendor/inter-latin.woff2'))) {
  const b64 = readFileSync(p('vendor/inter-latin.woff2')).toString('base64');
  if (b64.length <= 150 * 1024)   // otherwise the system-ui stack is used
    font = `@font-face { font-family: "Inter Reader"; font-style: normal; font-weight: 400 700; font-display: swap; src: url(data:font/woff2;base64,${b64}) format("woff2"); }`;
}

const scripts = [
  script('pako 2.1.0 (inflate only), MIT and Zlib; used only where DecompressionStream is missing', readFileSync(p('vendor/pako_inflate.min.js'), 'utf8')),
  script('jsQR 1.4.0, Apache-2.0', readFileSync(p('vendor/jsQR.min.js'), 'utf8')),
  script('jsPDF 2.5.2, MIT', readFileSync(p('vendor/jspdf.umd.min.js'), 'utf8')),
  script('Qalam payload decoder (web/src/decode.js)', readFileSync(p('src/decode.js'), 'utf8')),
  script('Qalam Reader (web/src/app.js)', readFileSync(p('src/app.js'), 'utf8')),
].join('\n');

const html = readFileSync(p('src/reader.template.html'), 'utf8').replace('/*__FONT__*/', () => font).replace('<!--__SCRIPTS__-->', () => scripts);

// zero network: nothing in the page may point outside itself
const external = html.match(/(?:src|href)\s*=\s*["'](?:https?:)?\/\/[^"']+/gi) || [];
if (external.length) { console.error('external references found:', external); process.exit(1); }
writeFileSync(p('reader.html'), html);
const kb = (Buffer.byteLength(html) / 1024).toFixed(0);
console.log(`reader.html ${kb} KB (font ${font ? 'Inter embedded' : 'system-ui'})`);
if (Buffer.byteLength(html) > 1024 * 1024) { console.error('over 1 MB'); process.exit(1); }

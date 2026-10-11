#!/bin/bash
# Builds web/deploy/: the static folder to upload to Cloudflare Pages (or any static host). No build step on the host.
# The page is served as index.html so the site root opens the reader directly.
set -e
cd "$(dirname "$0")"
rm -rf deploy && mkdir deploy
cp reader.html deploy/index.html
cp icon.svg deploy/icon.svg
sed 's|"start_url": "reader.html"|"start_url": "./"|' manifest.json > deploy/manifest.json
sed "s|const FILES = \['reader.html', 'manifest.json', 'icon.svg'\];|const FILES = ['./', 'manifest.json', 'icon.svg'];|" sw.js > deploy/sw.js
cat > deploy/_headers <<'H'
/*
  Permissions-Policy: camera=(self)
  X-Content-Type-Options: nosniff
  Referrer-Policy: no-referrer
/sw.js
  Cache-Control: no-cache
H
ls -la deploy

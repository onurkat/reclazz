#!/usr/bin/env python3
"""Measure rendered home headlines in Chrome. Run after build-docs-site.py."""
import argparse
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import tempfile
from threading import Thread

PROBE = b"""<!doctype html><title>Home layout check</title><pre id="result">pending</pre>
<script>
async function check(width) {
  localStorage.setItem('reclazz-lang', 'en');
  const frame = document.createElement('iframe');
  frame.style.cssText = 'border:0;height:900px;width:' + width + 'px';
  const loaded = new Promise(resolve => frame.onload = resolve);
  frame.src = '/';
  document.body.append(frame);
  await loaded;
  const doc = frame.contentDocument;
  const toggle = doc.getElementById('langToggle');
  for (const lang of ['en', 'tr']) {
    if (doc.documentElement.lang !== lang) {
      const translated = new Promise(resolve => {
        const observer = new MutationObserver(() => {
          if (doc.documentElement.lang === lang) { observer.disconnect(); resolve(); }
        });
        observer.observe(doc.documentElement, {attributes:true, attributeFilter:['lang']});
      });
      toggle.click();
      await translated;
    }
    await doc.fonts.ready;
    const range = doc.createRange();
    range.selectNodeContents(doc.querySelector('.hero-copy h1 span'));
    const lines = new Set([...range.getClientRects()].map(r => Math.round(r.top))).size;
    if (lines < 1 || lines > 3) throw new Error(lang + ' at ' + width + 'px: ' + lines + ' lines');
  }
  frame.remove();
}
(async () => {
  const result = document.getElementById('result');
  // Use separate windows sequentially: the language toggle persists its choice across loads.
  try {
    for (const width of [1024, 1280, 1440, 1600, 1920, 2560]) await check(width);
    result.textContent = 'PASS';
  } catch (e) { result.textContent = String(e); }
})();
</script>"""


class Site(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/__home_layout__':
            return super().do_GET()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.end_headers()
        self.wfile.write(PROBE)

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, default=Path('build/site'))
    parser.add_argument('--browser', default=shutil.which('google-chrome') or shutil.which('chromium'))
    args = parser.parse_args()
    if not args.browser or not (args.site / 'index.html').is_file():
        parser.error('Supply a Chrome executable and a built site')
    handler = functools.partial(Site, directory=str(args.site.resolve()))
    with ThreadingHTTPServer(('127.0.0.1', 0), handler) as server, tempfile.TemporaryDirectory() as profile:
        Thread(target=server.serve_forever, daemon=True).start()
        try:
            result = subprocess.run([args.browser, '--headless', '--disable-background-networking',
                                     '--no-first-run', '--user-data-dir=' + profile, '--dump-dom',
                                     '--virtual-time-budget=20000', f'http://127.0.0.1:{server.server_port}/__home_layout__'],
                                    capture_output=True, text=True, timeout=90, check=True)
        finally:
            server.shutdown()
    output = result.stdout.partition('<pre id="result">')[2].partition('</pre>')[0]
    if output != 'PASS':
        raise SystemExit('Home headline check failed: ' + (output or 'missing result'))
    print('PASS: English and Turkish headlines, six desktop widths, at most three lines')


if __name__ == '__main__':
    main()

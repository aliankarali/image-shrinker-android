#!/usr/bin/env bash
# Regenerates app/src/main/assets/svgo.js from the published svgo package.
#
# svgo ships its browser bundle as an ES module. QuickJS evaluates it as a
# plain script, so the trailing `export {…}` is rewritten into a global
# (`globalThis.svgo`) — the same transformation the macOS app applies for
# JavaScriptCore, so both run the identical optimiser.
set -euo pipefail

SVGO_VERSION=4.1.0
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/svgo.js"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

(cd "$WORK" && npm pack "svgo@$SVGO_VERSION" --silent >/dev/null && tar xzf "svgo-$SVGO_VERSION.tgz")

python3 -I - "$WORK/package/dist/svgo.browser.js" "$DEST" <<'PY'
import re, sys, pathlib
src = pathlib.Path(sys.argv[1]).read_text()
new, n = re.subn(
    r'export\{([^}]*)\}\s*$',
    lambda m: "globalThis.svgo={" + ",".join(x.strip() for x in m.group(1).split(",")) + "};",
    src,
)
if n != 1:
    raise SystemExit(f"expected exactly 1 trailing ESM export, found {n} — svgo bundle format changed")
if re.search(r'(?<![A-Za-z0-9_$])import(?=[\s{(.])', new):
    raise SystemExit("bundle contains import statements; QuickJS cannot resolve them")
pathlib.Path(sys.argv[2]).write_text(new)
print(f"wrote {sys.argv[2]} ({len(new)} bytes)")
PY

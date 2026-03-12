#!/usr/bin/env bash
#
# Generates PNG + WebP test fixtures via Chrome headless (canvas.toDataURL).
#
set -euo pipefail

DIR=imageio-native-common/src/test/resources

# ── Locate Chrome ────────────────────────────────────────────────────

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
if [[ ! -x "$CHROME" ]]; then
    echo "Error: Google Chrome not found at $CHROME" >&2
    exit 1
fi

# ── Generate PNG + WebP via Chrome headless ──────────────────────────

HTML=$(mktemp /tmp/gen-test-images-XXXXXX.html)
trap 'rm -f "$HTML"' EXIT

cat > "$HTML" << 'HTMLEOF'
<canvas id="c" width="8" height="8"></canvas>
<script>
const c = document.getElementById('c').getContext('2d');
c.fillStyle='#ff0000'; c.fillRect(0,0,4,4);
c.fillStyle='#00ff00'; c.fillRect(4,0,4,4);
c.fillStyle='#0000ff'; c.fillRect(0,4,4,4);
c.fillStyle='#ffffff'; c.fillRect(4,4,4,4);
const canvas = document.getElementById('c');
const png  = canvas.toDataURL('image/png').split(',')[1];
const webp = canvas.toDataURL('image/webp', 1.0).split(',')[1];
document.title = JSON.stringify({png, webp});
</script>
HTMLEOF

echo "Generating PNG + WebP test images via Chrome:"
DOM=$("$CHROME" --headless=new --no-sandbox --disable-gpu \
    --disable-software-rasterizer --dump-dom "file://$HTML" 2>/dev/null)

# Extract base64 payloads from <title>JSON</title>
JSON=$(echo "$DOM" | sed -n 's/.*<title>\(.*\)<\/title>.*/\1/p')

mkdir -p "$DIR"
echo "$JSON" | python3 -c "
import json, base64, sys
d = json.load(sys.stdin)
open('$DIR/test8x8.png',  'wb').write(base64.b64decode(d['png']))
open('$DIR/test8x8.webp', 'wb').write(base64.b64decode(d['webp']))
"
echo "  $DIR/test8x8.png"
echo "  $DIR/test8x8.webp"

echo "Done – 2 files in $DIR"

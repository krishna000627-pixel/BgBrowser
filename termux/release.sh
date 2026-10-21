#!/data/data/com.termux/files/usr/bin/bash
# Use after every change:  bash termux/release.sh "what I changed"
# Commits, pushes, waits for the GitHub Actions build, downloads the signed APK and opens the installer.
set -euo pipefail
cd "$(dirname "$0")/.."

MSG="${1:-update}"
if [ "$MSG" != "--no-commit" ]; then
  git add -A
  git diff --cached --quiet || git commit -m "$MSG"
  git push origin HEAD
fi

SLUG="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
SHA="$(git rev-parse HEAD)"

echo "==> Waiting for CI run of ${SHA:0:7}"
RUN_ID=""
for _ in $(seq 1 20); do
  RUN_ID="$(gh run list --commit "$SHA" --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)"
  [ -n "$RUN_ID" ] && break
  sleep 4
done
[ -n "$RUN_ID" ] || { echo "No CI run found. Check the Actions tab."; exit 1; }
if ! gh run watch "$RUN_ID" --exit-status; then
  echo "==> BUILD FAILED. Compile errors:"
  gh run view "$RUN_ID" --log-failed 2>/dev/null | grep -o "e: file.*" | sed 's#file:///home/runner/work/[^/]*/[^/]*/app/src/main/java/com/bgbrowser/##' | sort -u | head -15 | tee "$HOME/build_errors.txt"
  echo "==> Paste these lines to Claude (also saved in ~/build_errors.txt)"
  exit 1
fi

OUT="$HOME/storage/downloads"
[ -d "$OUT" ] || { echo "Tip: run termux-setup-storage once to save to Downloads."; OUT="$HOME"; }
gh release download --repo "$SLUG" --pattern '*.apk' --dir "$OUT" --clobber
APK="$OUT/app-release.apk"
echo "==> APK saved: $APK"
command -v termux-open >/dev/null && termux-open "$APK" || true

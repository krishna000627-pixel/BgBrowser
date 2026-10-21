#!/data/data/com.termux/files/usr/bin/bash
# One-time setup, run from Termux:  bash termux/setup.sh [repo-name]
#   1. installs git, gh (and a JDK only if keytool is missing)
#   2. creates a signing keystore (JKS) outside the repo
#   3. creates the GitHub repo and stores the keystore as Actions secrets
#   4. pushes the code, waits for CI, downloads and opens the signed APK
set -euo pipefail

GH_USER="krishna000627-pixel"
REPO="${1:-bgbrowser}"
KS_DIR="$HOME/.bgbrowser"
KS_FILE="$KS_DIR/release.jks"
KS_PROPS="$KS_DIR/keystore.properties"
KS_ALIAS="bgbrowser"

cd "$(dirname "$0")/.."

echo "==> Installing tools"
pkg install -y git gh
command -v keytool >/dev/null 2>&1 || pkg install -y openjdk-17

echo "==> GitHub login"
gh auth status >/dev/null 2>&1 || gh auth login
gh auth setup-git

echo "==> Keystore"
mkdir -p "$KS_DIR"
chmod 700 "$KS_DIR"
if [ ! -f "$KS_FILE" ]; then
  PASS="$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  keytool -genkeypair -v \
    -keystore "$KS_FILE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=BgBrowser, O=Hobby"
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
    "$KS_FILE" "$PASS" "$KS_ALIAS" "$PASS" > "$KS_PROPS"
  chmod 600 "$KS_FILE" "$KS_PROPS"
  echo "Keystore created in $KS_DIR. BACK THIS FOLDER UP: losing it means you can't update the installed app."
fi
PASS="$(grep '^storePassword=' "$KS_PROPS" | cut -d= -f2-)"

echo "==> Git repo"
[ -d .git ] || git init -b main
git config user.name >/dev/null || git config user.name "$GH_USER"
git config user.email >/dev/null || git config user.email "$GH_USER@users.noreply.github.com"
git add -A
git diff --cached --quiet || git commit -m "Initial commit"

if ! gh repo view "$GH_USER/$REPO" >/dev/null 2>&1; then
  gh repo create "$GH_USER/$REPO" --private --source=. --remote=origin
fi
git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$GH_USER/$REPO.git"

echo "==> Uploading signing secrets"
base64 -w0 "$KS_FILE" | gh secret set KEYSTORE_BASE64 --repo "$GH_USER/$REPO"
gh secret set KEYSTORE_PASSWORD --repo "$GH_USER/$REPO" --body "$PASS"
gh secret set KEY_PASSWORD      --repo "$GH_USER/$REPO" --body "$PASS"
gh secret set KEY_ALIAS         --repo "$GH_USER/$REPO" --body "$KS_ALIAS"

echo "==> Pushing"
git push -u origin main

bash termux/release.sh --no-commit

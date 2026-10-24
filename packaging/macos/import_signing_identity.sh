#!/usr/bin/env bash
# Import CineRemote's self-signed code-signing identity into a temporary keychain and print its SHA-1 hash
# (pass that to build_app.sh as SIGN_IDENTITY). Used by CI; works the same on a Mac for testing.
#
#   MACOS_SIGN_P12=<base64 .p12>  MACOS_SIGN_P12_PASSWORD=<password>  bash import_signing_identity.sh
#
# Why: an ad-hoc signature changes with every build, so macOS treats each update as a different app and
# privacy grants (Local Network!) don't carry over. A stable certificate gives every build the same
# designated requirement (bundle id + certificate), so they do. It is NOT an Apple Developer ID: the
# first-launch "could not verify" prompt stays until the app is notarized.
set -euo pipefail
: "${MACOS_SIGN_P12:?base64 of the .p12}" "${MACOS_SIGN_P12_PASSWORD:?.p12 password}"
TMP=${RUNNER_TEMP:-$(mktemp -d)}
KC="$TMP/cineremote-signing.keychain-db"
KCPW=$(openssl rand -hex 16)
P12="$TMP/cineremote-signing.p12"
printf "%s" "$MACOS_SIGN_P12" | base64 --decode > "$P12"
security delete-keychain "$KC" >/dev/null 2>&1 || true
security create-keychain -p "$KCPW" "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p "$KCPW" "$KC"
security import "$P12" -k "$KC" -P "$MACOS_SIGN_P12_PASSWORD" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KCPW" "$KC" >/dev/null
# put it on the search list so codesign finds the identity
security list-keychains -d user -s "$KC" $(security list-keychains -d user | tr -d '"')
rm -f "$P12"
# self-signed = untrusted, so don't filter with -v: take the identity's hash
HASH=$(security find-identity -p codesigning "$KC" | awk '/CineRemote Code Signing/{print $2; exit}')
[ -n "$HASH" ] || { echo "signing identity not found after import" >&2; exit 1; }
echo "$HASH"

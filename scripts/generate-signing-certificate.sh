#!/usr/bin/env bash
#
# Generates the signing key and certificate the JetBrains Marketplace
# requires for plugin uploads.
#
# Run it once, interactively, from the repository root:
#
#     ./scripts/generate-signing-certificate.sh
#
# It asks for a passphrase and never writes it anywhere: only you know
# it. The private key it produces can sign updates as you, so treat it
# like a password manager entry, not like a project file.
#
# Output (both git-ignored, see .gitignore):
#   certificate/private.pem   encrypted RSA-4096 private key
#   certificate/chain.crt     self-signed X.509 certificate, 10 years
#
set -euo pipefail

CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/certificate"
KEY="$CERT_DIR/private.pem"
CRT="$CERT_DIR/chain.crt"

if [[ -e "$KEY" || -e "$CRT" ]]; then
    echo "Refusing to overwrite existing files in $CERT_DIR"
    echo "Losing the current key means you cannot ship updates signed as"
    echo "the same identity. Move them aside deliberately if that is what"
    echo "you want."
    exit 1
fi

mkdir -p "$CERT_DIR"
chmod 700 "$CERT_DIR"

echo "A passphrase protects the private key. Choose one you can retrieve"
echo "later: signing every future release needs it, and it cannot be"
echo "recovered. Store it in your password manager now."
echo

# openssl prompts for the passphrase itself, twice, with no echo. The
# value never reaches this script, the shell history, or the terminal.
openssl genpkey \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:4096 \
    -aes256 \
    -out "$KEY"

chmod 600 "$KEY"

echo
echo "Now the certificate. The Common Name is what identifies the signer;"
echo "the defaults below are reasonable if you just press enter."
echo

openssl req \
    -key "$KEY" \
    -new -x509 \
    -days 3650 \
    -out "$CRT" \
    -subj "/CN=Onur Kat/O=Onur Kat/C=TR"

chmod 644 "$CRT"

echo
echo "Done:"
openssl x509 -in "$CRT" -noout -subject -enddate
echo
echo "  $KEY"
echo "  $CRT"
echo
echo "Next, export the passphrase before signing or publishing:"
echo
echo "    export RECLAZZ_SIGNING_PASSWORD='...'"
echo "    export RECLAZZ_PUBLISH_TOKEN='...'   # from plugins.jetbrains.com"
echo
echo "Then:  ./gradlew signPlugin      (produces a signed zip)"
echo "       ./gradlew publishPlugin   (uploads it)"
echo
echo "Keep certificate/ out of git. It already is; verify with:"
echo "    git check-ignore -v certificate/private.pem"

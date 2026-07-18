#!/usr/bin/env bash
set -euo pipefail
umask 077

OUT_DIR="${1:-target/generated-keys}"
if [[ -d "$OUT_DIR" ]]; then
  for artifact in private.der public.der private.pem.b64 public.pem.b64; do
    if [[ -e "$OUT_DIR/$artifact" || -L "$OUT_DIR/$artifact" ]]; then
      echo "Refusing to overwrite generated key artifacts in $OUT_DIR" >&2
      exit 1
    fi
  done
fi

mkdir -p "$OUT_DIR"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -outform DER -out "$OUT_DIR/private.der"

openssl pkey -in "$OUT_DIR/private.der" -inform DER -pubout \
  -outform DER -out "$OUT_DIR/public.der"

base64 -w0 "$OUT_DIR/private.der" > "$OUT_DIR/private.pem.b64"
base64 -w0 "$OUT_DIR/public.der" > "$OUT_DIR/public.pem.b64"

echo "Set env:"
echo "  export AUTH_JWT_PRIVATE_KEY_PEM=\"\$(cat $OUT_DIR/private.pem.b64)\""
echo "  export AUTH_JWT_ACTIVE_PUBLIC_KEY_PEM=\"\$(cat $OUT_DIR/public.pem.b64)\""
echo "  export AUTH_JWT_ACTIVE_KID=\"auth-key-2026-01\""

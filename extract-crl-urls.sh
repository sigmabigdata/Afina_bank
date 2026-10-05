#!/usr/bin/env bash
# Извлекает CRL Distribution Points URL из сертификата.
# Использование: ./extract-crl-urls.sh cert.cer
set -e

if [ -z "$1" ] || [ ! -f "$1" ]; then
    echo "Использование: $0 <cert.cer | cert.pem | cert.crt>"
    exit 1
fi

CERT="$1"

# Определяем формат
if head -c 30 "$CERT" | grep -q "BEGIN CERTIFICATE"; then
    FORMAT="PEM"
else
    FORMAT="DER"
fi

echo "Файл: $CERT (формат: $FORMAT)"
echo ""
echo "=== Subject ==="
openssl x509 -in "$CERT" -inform "$FORMAT" -noout -subject

echo ""
echo "=== Issuer ==="
openssl x509 -in "$CERT" -inform "$FORMAT" -noout -issuer

echo ""
echo "=== CRL Distribution Points ==="
openssl x509 -in "$CERT" -inform "$FORMAT" -noout -text | \
  awk '/X509v3 CRL Distribution Points/,/^$/' | \
  grep -oE 'URI:[^ ]+' | sed 's/URI://' | sort -u

echo ""
echo "=== Authority Information Access (OCSP) ==="
openssl x509 -in "$CERT" -inform "$FORMAT" -noout -text | \
  awk '/Authority Information Access/,/^$/' | grep -oE 'URI:[^ ]+' | sed 's/URI://'

echo ""
echo "=== Subject Key Identifier / Authority Key Identifier ==="
openssl x509 -in "$CERT" -inform "$FORMAT" -noout -text | \
  grep -E 'Subject Key Identifier|Authority Key Identifier' -A1 | head -6

#!/bin/sh
# Publie l'APK construit (sortie/MBSC-Finapp-<version>.apk) sur le site : ../telechargements/MBSC-Finapp.apk et
# version.json (version, taille, date), servis par nginx sous /telechargements/ et liés depuis la page À propos.
# Usage : sh outils/publier-apk.sh   (depuis mobile-android/, après construire-apk.sh)
set -e
cd "$(dirname "$0")/.."
VERSION=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
SOURCE="sortie/MBSC-Finapp-$VERSION.apk"
[ -f "$SOURCE" ] || { echo "APK introuvable : $SOURCE (lancer outils/construire-apk.sh)"; exit 1; }
DEST=../telechargements
mkdir -p "$DEST"
cp "$SOURCE" "$DEST/MBSC-Finapp.apk.tmp" && mv "$DEST/MBSC-Finapp.apk.tmp" "$DEST/MBSC-Finapp.apk"
chmod 644 "$DEST/MBSC-Finapp.apk"
TAILLE=$(stat -c %s "$DEST/MBSC-Finapp.apk")
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '{"version":"%s","taille":%s,"date":"%s","fichier":"MBSC-Finapp.apk"}\n' "$VERSION" "$TAILLE" "$DATE" > "$DEST/version.json"
chmod 644 "$DEST/version.json"
echo "Publié : version $VERSION ($TAILLE octets) -> $DEST/MBSC-Finapp.apk"

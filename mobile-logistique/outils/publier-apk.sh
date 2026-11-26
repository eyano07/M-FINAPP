#!/bin/sh
# Publie l'APK construit (sortie/MBSC-Logistique-<version>.apk) sur le site : ../telechargements/MBSC-Logistique.apk et
# version-logistique.json (version, taille, date), servis par nginx sous /telechargements/ et liés depuis la page À propos.
# Usage : sh outils/publier-apk.sh   (depuis mobile-android/, après construire-apk.sh)
set -e
cd "$(dirname "$0")/.."
VERSION=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
SOURCE="sortie/MBSC-Logistique-$VERSION.apk"
[ -f "$SOURCE" ] || { echo "APK introuvable : $SOURCE (lancer outils/construire-apk.sh)"; exit 1; }
DEST=../telechargements
mkdir -p "$DEST"
cp "$SOURCE" "$DEST/MBSC-Logistique.apk.tmp" && mv "$DEST/MBSC-Logistique.apk.tmp" "$DEST/MBSC-Logistique.apk"
chmod 644 "$DEST/MBSC-Logistique.apk"
TAILLE=$(stat -c %s "$DEST/MBSC-Logistique.apk")
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '{"version":"%s","taille":%s,"date":"%s","fichier":"MBSC-Logistique.apk"}\n' "$VERSION" "$TAILLE" "$DATE" > "$DEST/version-logistique.json"
chmod 644 "$DEST/version-logistique.json"
echo "Publié : version $VERSION ($TAILLE octets) -> $DEST/MBSC-Logistique.apk"

#!/bin/sh
# Construit l'APK signé de MBSC Finapp dans Docker (aucun SDK Android sur la machine), à 1,5 cœur.
#   sh outils/construire-apk.sh            -> sortie/MBSC-Finapp-<version>.apk
# Première fois : crée l'image finapp-android-build:1 et la clé de signature (signature/finapp.jks + signature.properties).
# ⚠ La clé de signature n'est pas dans le dépôt : la sauvegarder. Sans elle, une mise à jour ne s'installe plus par-dessus
#   l'application (il faudrait la désinstaller sur chaque téléphone).
set -e
cd "$(dirname "$0")/.."
PROJET=$(pwd)
IMAGE=finapp-android-build:1
CACHE="${HOME}/.cache/finapp-gradle"
mkdir -p "$CACHE" sortie

[ -f app/google-services.json ] || { echo "app/google-services.json manquant (console Firebase, application cd.mbsc.finapp)."; exit 1; }
docker image inspect $IMAGE > /dev/null 2>&1 || docker build -t $IMAGE outils

if [ ! -f signature.properties ]; then
  echo "Création de la clé de signature (signature/finapp.jks)…"
  mkdir -p signature
  MDP=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  docker run --rm -u "$(id -u):$(id -g)" -v "$PROJET/signature":/s $IMAGE \
    keytool -genkeypair -keystore /s/finapp.jks -alias finapp -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$MDP" -keypass "$MDP" -dname "CN=MBSC Finapp, O=MBSC, C=CD" > /dev/null
  ( umask 077; printf 'storeFile=signature/finapp.jks\nstorePassword=%s\nkeyAlias=finapp\nkeyPassword=%s\n' "$MDP" "$MDP" > signature.properties )
  chmod 600 signature.properties signature/finapp.jks
fi

docker run --rm --cpus "${CPUS:-1.5}" --memory 3g -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/cache -e ANDROID_HOME=/opt/android-sdk \
  -v "$CACHE":/cache -v "$PROJET":/projet -w /projet $IMAGE \
  gradle --no-daemon -q assembleRelease

VERSION=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)
cp app/build/outputs/apk/release/app-release.apk "sortie/MBSC-Finapp-$VERSION.apk"
ls -l "sortie/MBSC-Finapp-$VERSION.apk"

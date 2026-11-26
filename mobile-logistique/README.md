# Application Android MBSC Logistique

Le site MBSC Finapp dans une application Android pour la **logistique**, l'**approvisionnement** et le **patrimoine**,
avec un **scanner de QR** : le bouton rond « Scanner » (ou l'appui long sur l'icône → « Scanner un bien ») lit
l'étiquette collée sur un bien et ouvre sa fiche (`/patrimoine/bien?code=…`) : emplacement, état, statut, photos,
facture et, s'il est immobilisé, valeur brute, amortissements, valeur nette, fournisseur et numéro de série.

## Fonctionnement

- **Premier lancement** : l'adresse du serveur (`192.168.1.10`, `https://erp.exemple.com`), testée sur `/api/health`.
  Pour la changer : appui long sur l'icône → « Changer de serveur ».
- **Droits** : ceux du compte connecté, comme sur le site. L'application s'annonce `MBSCLogistiqueAndroid` (et non
  `MBSCFinappAndroid`), elle n'est donc pas limitée par `ApplicationMobileFilter`.
- **Scanner** : ZXing (`zxing-android-embedded`), sans services Google ; autorisation de l'appareil photo demandée au
  premier scan. Pas de Firebase, donc pas de `google-services.json`.
- **Fichiers** : PDF et Excel du site enregistrés dans `Téléchargements/MBSC Logistique` puis ouverts ; impression
  Android (étiquettes, PV d'inventaire) ; photos des biens depuis l'appareil photo.

## Construire et publier l'APK

```bash
sh outils/construire-apk.sh     # sortie/MBSC-Logistique-<version>.apk
sh outils/publier-apk.sh        # ../telechargements/MBSC-Logistique.apk + version-logistique.json
```

Hors dépôt : `signature/`, `signature.properties`. **Sauvegarder la clé de signature** : les mises à jour doivent être
signées avec la même clé. Pour une nouvelle version, augmenter `versionCode` et `versionName` dans `app/build.gradle`.

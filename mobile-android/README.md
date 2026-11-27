# Application Android MBSC Finapp

Le site MBSC Finapp dans une application Android, pour **valider les notes de frais et les budgets** et **consulter** le
tableau de bord, les notes de frais, la comptabilité et les budgets (suivi de chaque budget, budget consolidé).
Notifications push (Firebase) pour les notes à vérifier ou à approuver et pour les budgets (canal « Budgets »).

## Fonctionnement

- **Premier lancement** : l'utilisateur saisit l'adresse du serveur (`192.168.1.10`, `192.168.1.10:8080`,
  `https://erp.exemple.com`). « Tester » vérifie que c'est bien un serveur MBSC Finapp (`/api/health`).
  Pour la changer : appui long sur l'icône → « Changer de serveur », ou bouton de l'écran d'erreur.
- **Périmètre** : l'application ajoute `MBSCFinappAndroid` à l'agent utilisateur. Le serveur
  (`ApplicationMobileFilter`) n'y accepte que la consultation et les décisions sur une note (vérifier, transmettre,
  approuver, rejeter, observation, priorité) et sur un budget (soumettre, approuver, rejeter, mettre en exécution,
  clôturer) ; le site n'y montre que le tableau de bord, les notes de frais, la
  comptabilité et les budgets (`utils/applicationMobile.ts`).
- **Fichiers** : PDF et Excel du site enregistrés dans `Téléchargements/MBSC Finapp` puis ouverts ; impression par
  le service d'impression Android ; pièces jointes depuis les fichiers ou l'appareil photo.
- **Notifications push** : le téléphone s'inscrit au compte connecté (`POST /api/appareils`) et le quitte à la
  déconnexion. Le serveur envoie les notifications des notes de frais et des budgets si la clé du compte de service Firebase est
  présente : `secrets/firebase-compte-service.json` à la racine du projet (monté dans le conteneur backend).
  Un serveur sans accès à Internet ne peut pas envoyer de notifications push.

## Construire l'APK

```bash
sh outils/construire-apk.sh
```

Il faut `app/google-services.json` (console Firebase, application `cd.mbsc.finapp`). Hors dépôt (public) :
`app/google-services.json`, `signature/`, `signature.properties`. **Sauvegarder la clé de signature** : les mises à
jour doivent être signées avec la même clé. Pour une nouvelle version, augmenter `versionCode` dans `app/build.gradle`.

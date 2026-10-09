# M-FINAPP — mise en production sur un serveur (VPS)

Ce dossier contient tout ce qu'il faut pour installer M-FINAPP sur un serveur Linux : l'application
(images Docker), une base de données **vierge** (plan comptable + un compte administrateur), la
configuration et les scripts. Le site sera joignable sur **http://ADRESSE-DU-SERVEUR:9090**.

| Fichier | Rôle |
|---|---|
| `installer.sh` | installation, à lancer **une seule fois** |
| `definir-admin.sh` | (re)définit l'e-mail et le mot de passe de l'administrateur |
| `sauvegarder.sh` | sauvegarde la base et les fichiers téléversés |
| `restaurer-base.sh` | restaure une sauvegarde |
| `mettre-a-jour.sh` | installe une nouvelle version (livraison `--mise-a-jour`) |
| `.env` | secrets du serveur (mots de passe, clé des sessions) — **confidentiel** |
| `docker-compose.prod.yml`, `nginx/` | configuration (nginx publié sur le port 9090) |
| `docker-compose.tls.yml`, `nginx/nginx.prod.conf` | option HTTPS, pour plus tard (rubrique 7) |

## À prévoir

- Un serveur **Ubuntu 22.04 ou 24.04** (ou Debian 12), accès SSH, **2 processeurs, 4 Go de mémoire, 20 Go de disque** au minimum.
- L'**adresse IP** du serveur (et un nom de domaine si vous en avez un).
- Le **port 9090 ouvert** : dans le pare-feu de votre hébergeur (s'il en a un) et sur le serveur (étape 1).

## 1. Préparer le serveur (une seule fois)

Connectez-vous en SSH, puis :

```bash
sudo apt update && sudo apt -y upgrade
# Docker (dépôt officiel)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER        # puis se déconnecter / reconnecter pour que cela prenne effet
# Pare-feu : SSH + le site (le port SSH est autorisé AVANT d'activer le pare-feu)
sudo ufw allow OpenSSH
sudo ufw allow 9090/tcp
sudo ufw enable
# Protection contre les tentatives de connexion SSH répétées + mises à jour de sécurité automatiques
sudo apt -y install fail2ban unattended-upgrades
# Dossier d'installation
sudo mkdir -p /opt/mbsc && sudo chown $USER /opt/mbsc
```

## 2. Envoyer la livraison

Depuis l'ordinateur de développement (dossier `livraison/`), **le point final de `.` est important** : il envoie aussi le fichier caché `.env`.

```bash
scp -r livraison/. utilisateur@ADRESSE-DU-SERVEUR:/opt/mbsc/
```

Le transfert prend quelques minutes (environ 300 Mo). L'installateur vérifie ensuite que rien n'a été abîmé.

## 3. Installer

```bash
cd /opt/mbsc
./installer.sh
```

Il vous demande :

1. **l'adresse du site** : l'adresse IP du serveur (ou son nom de domaine) — elle est autorisée dans le serveur ; si vous ouvrez le site par une autre adresse, les enregistrements seront refusés (voir Dépannage) ;
2. **l'e-mail et le nouveau mot de passe de l'administrateur** (12 caractères au moins). **Le compte livré est verrouillé** : l'ancien mot de passe par défaut, publié avec le code sur GitHub, ne fonctionne pas et ne doit plus jamais être utilisé.

Puis il charge les images, crée la base, restaure le plan comptable, démarre l'application et contrôle qu'elle répond. Compter 3 à 6 minutes.
Le relancer est sans danger : chaque étape vérifie d'abord son état.

## 4. Première connexion et configuration

Ouvrez `http://ADRESSE-DU-SERVEUR:9090` et connectez-vous avec le compte administrateur. La base est vide : dans **Administration**, renseignez dans cet ordre :

1. les **paramètres de l'entreprise** (nom, adresse, RCCM, Id.Nat, NIF, logo) ;
2. le **taux de change** — à saisir **en premier**, beaucoup d'écrans en dépendent ;
3. la **TVA** (16 % si l'entreprise y est assujettie) ;
4. les **établissements** de trésorerie (caisses, banques, mobile money) et les **entrepôts** ;
5. les **utilisateurs** : chacun reçoit automatiquement les droits d'origine de son rôle ; ajustez-les dans *Droits par rôle* si besoin. Utilisez le compte administrateur le moins possible : créez des comptes nominatifs ;
6. l'**import du journal d'ouverture** (soldes de départ).

## 5. Sécurité — à lire avant d'ouvrir au public

- **Le site est en HTTP simple : le trafic n'est pas chiffré.** Mots de passe et données financières circulent en clair entre le navigateur et le serveur. Tant que vous n'avez pas de HTTPS (rubrique 7), limitez l'accès :
  - **par pare-feu**, aux adresses IP de l'entreprise : `sudo ufw delete allow 9090/tcp` puis `sudo ufw allow from ADRESSE_IP to any port 9090 proto tcp` (une ligne par adresse) ;
  - ou **par tunnel SSH** : mettez `HTTP_PORT=127.0.0.1:9090` dans `.env`, `docker compose up -d`, fermez le port 9090 dans le pare-feu, et chaque utilisateur ouvre `ssh -L 9090:127.0.0.1:9090 utilisateur@serveur` puis `http://localhost:9090`.
- Le fichier `.env` contient les accès à la base et la clé des sessions : droits `600`, jamais publié ni commité. Gardez-en une copie dans un gestionnaire de mots de passe.
- Aucun compte de démonstration n'existe et la documentation de l'API est fermée.
- Le serveur de base de données n'est joignable que par l'application (aucun port publié) et l'application s'y connecte avec un compte sans droits de super-utilisateur.
- Le dispositif anti-force-brute bloque un compte après 5 échecs en 15 minutes ; nginx limite les tentatives de connexion par adresse IP.

## 6. Sauvegardes et mises à jour

**Sauvegarde** (base + fichiers téléversés, sauvegardes de plus de 14 jours supprimées) :

```bash
cd /opt/mbsc && ./sauvegarder.sh                # dossier : /opt/mbsc/sauvegardes/
crontab -e                                       # puis ajouter, pour une sauvegarde chaque nuit à 2 h :
0 2 * * * cd /opt/mbsc && ./sauvegarder.sh >> sauvegardes/journal.log 2>&1
```

Une sauvegarde qui reste **sur** le serveur ne protège pas d'une panne du serveur : copiez régulièrement `sauvegardes/` ailleurs (par exemple, depuis un autre ordinateur : `rsync -av utilisateur@serveur:/opt/mbsc/sauvegardes/ ./sauvegardes-mbsc/`). Essayez une restauration de temps en temps.

**Restaurer** : `./restaurer-base.sh sauvegardes/base_AAAAMMJJ_HHMMSS.dump --remplacer` (l'application est arrêtée pendant l'opération ; une sauvegarde de sécurité est prise avant) puis `docker compose up -d`.

**Mettre à jour** (nouvelle version du logiciel) : sur l'ordinateur de développement, `deploiement/preparer-livraison.sh --mise-a-jour`, puis
`scp -r livraison-maj/. utilisateur@serveur:/opt/mbsc/` (cela ne touche ni `.env`, ni la base, ni les sauvegardes) et, sur le serveur, `./mettre-a-jour.sh`. La base est migrée automatiquement ; une sauvegarde complète est prise avant.

## 7. HTTPS plus tard (quand vous aurez un nom de domaine)

Le HTTPS exige un **nom de domaine** pointant vers le serveur, et les **ports 80 et 443 libres** (le port 80 sert au certificat Let's Encrypt et à la redirection). Les fichiers sont prêts (`docker-compose.tls.yml`, `nginx/nginx.prod.conf`) mais **cette partie n'a pas pu être essayée avec un vrai domaine** : demandez de l'aide pour l'activer le moment venu, ou suivez les commentaires de ces deux fichiers.

## 8. Une seconde instance sur un serveur qui en héberge déjà une (instance de test)

Deux instances peuvent tourner sur le même serveur (par exemple une instance de **test** à côté de la **production**) à condition de ne rien partager. Dans le `.env` de la seconde :

```bash
COMPOSE_PROJECT_NAME=mfinapp-test     # volumes et réseau propres : mfinapp-test_db_data, mfinapp-test_mbsc-net...
CONTENEURS=mfinapp-test               # conteneurs mfinapp-test-db, mfinapp-test-backend...
COMPOSE_FILE=docker-compose.prod.yml:docker-compose.limites.yml   # ressources plafonnées (1 processeur, 1,5 Go pour le serveur)
HTTP_PORT=9090                        # un port que l'autre instance n'utilise pas
```

Elle a sa propre base, ses propres mots de passe et ses propres fichiers : rien ne passe de l'une à l'autre. **Toutes les commandes se lancent depuis son dossier** (`cd /root/M-FINAPP-test` avant `docker compose ...` ou un script) : lancées depuis le dossier de l'autre instance, elles agiraient sur l'autre instance.

## Dépannage

| Symptôme | Que faire |
|---|---|
| Voir l'état | `docker compose ps` ; journaux : `docker compose logs --tail 100 backend` (ou `nginx`, `frontend`, `db`) |
| Page blanche ou erreur 502 juste après un démarrage | attendre une minute (le serveur met 1 à 2 minutes à démarrer) ; sinon `docker compose restart nginx` |
| « Invalid CORS request » ou 403 à l'enregistrement d'une donnée | l'adresse tapée dans le navigateur n'est pas celle déclarée à l'installation : ajouter son adresse dans `.env` (`CORS_ORIGINS=http://adresse1:9090,http://adresse2:9090`) puis `docker compose up -d backend` |
| Mot de passe administrateur perdu | `./definir-admin.sh` |
| Port 9090 déjà utilisé | choisir un autre port : `HTTP_PORT=...` dans `.env`, `docker compose up -d`, adapter `CORS_ORIGINS` et le pare-feu |
| Disque plein | `docker system df` ; les journaux des conteneurs tournent (10 Mo × 5) ; réduire la durée de conservation : `./sauvegarder.sh --garder 7` |
| Redémarrage du serveur | tout redémarre seul (`restart: unless-stopped`) |

package cd.mbsc.finapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Application MBSC Finapp : le site du serveur de l'entreprise dans une vue web, avec ce qui manque à une vue web
 * (adresse du serveur, fichiers, appareil photo, impression, notifications push).
 *
 * <p>Au premier lancement, l'utilisateur saisit l'adresse du serveur (configuration.html) ; elle est gardée dans les
 * préférences et se change par l'appui long sur l'icône (« Changer de serveur ») ou depuis l'écran d'erreur. L'agent
 * utilisateur porte « MBSCFinappAndroid » : le serveur reconnaît ainsi l'application et n'y permet que la consultation
 * et les décisions sur les notes de frais (ApplicationMobileFilter).</p>
 */
public class MainActivity extends Activity {

    static final String PREFERENCES = "finapp";
    static final String CLE_SERVEUR = "serveur";
    static final String ACTION_CONFIGURER = "cd.mbsc.finapp.CONFIGURER";
    /** Lien de la note à ouvrir, porté par l'intention d'une notification. */
    static final String EXTRA_LIEN = "lien";
    /** Dernière page du serveur affichée (chemin), rouverte si Android a fermé l'application en arrière-plan. */
    static final String CLE_DERNIERE_PAGE = "derniere_page";
    /** Dernières couleurs du thème du site (barre d'état, barre de navigation, mode nuit), réappliquées au démarrage. */
    static final String CLE_THEME_ETAT = "theme_etat";
    static final String CLE_THEME_NAV = "theme_nav";
    static final String CLE_THEME_SOMBRE = "theme_sombre";

    private static final String PAGE_CONFIGURATION = "file:///android_asset/configuration.html";
    private static final String PAGE_ERREUR = "file:///android_asset/erreur.html";
    private static final int DEMANDE_FICHIERS = 1;
    private static final int PERMISSION_CAMERA_PAGE = 2;
    private static final int PERMISSION_CAMERA_FICHIERS = 3;
    private static final int PERMISSION_NOTIFICATIONS = 4;
    private static final int PERMISSION_CAMERA_SCAN = 5;

    /** Version de l'application (versionName), lue au démarrage. */
    private static String versionAppli = "?";

    private WebView vue;
    private SharedPreferences preferences;
    private String passerelle;
    private boolean effacerHistorique;

    // Hors connexion (18/11/2026) : la page reste affichée (copie du cache de la vue web au besoin) avec un message,
    // au lieu d'une page blanche ou de la page d'erreur.
    private ConnectivityManager reseau;
    private ConnectivityManager.NetworkCallback suiviReseau;
    private boolean enLigne = true;
    /** Page du serveur rechargée depuis le cache après un échec : à recharger du serveur au retour de la connexion. */
    private boolean pageDuCache;
    /** Adresse déjà retentée depuis le cache (évite de boucler si elle n'y est pas non plus). */
    private String essaiCache;
    private AlertDialog messageHorsLigne;

    private ValueCallback<Uri[]> choixFichiers;
    private WebChromeClient.FileChooserParams parametresChoix;
    private File photoEnCours;
    private PermissionRequest demandeCamera;

    // ---------------------------------------------------------------------
    // Cycle de vie
    // ---------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        passerelle = lireAsset("passerelle.js");
        Notifications.creerCanal(this);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        vue = new WebView(this);
        vue.setBackgroundColor(0xFFEEF2F0);
        String etatRetenu = preferences.getString(CLE_THEME_ETAT, null);
        String navRetenue = preferences.getString(CLE_THEME_NAV, null);
        if (etatRetenu != null && navRetenue != null) {
            appliquerTheme(etatRetenu, navRetenue, preferences.getBoolean(CLE_THEME_SOMBRE, false));
        }
        vue.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        setContentView(vue);
        configurerVue();

        if (etat != null && vue.restoreState(etat) != null) {
            // Rotation, retour après un manque de mémoire : la page reprend où elle était.
        } else {
            ouvrir(getIntent());
        }
        demanderNotifications();
        Notifications.rafraichirJeton(this);
        suivreReseau();
    }

    @Override
    protected void onDestroy() {
        if (reseau != null && suiviReseau != null) {
            try { reseau.unregisterNetworkCallback(suiviReseau); } catch (Exception ignore) { /* déjà retiré */ }
        }
        if (messageHorsLigne != null) messageHorsLigne.dismiss();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // Connexion
    // ---------------------------------------------------------------------

    /** Suit le réseau du téléphone : cache de la vue web hors ligne, message à la perte, rechargement au retour. */
    private void suivreReseau() {
        reseau = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (reseau == null) return;
        enLigne = connecte();
        appliquerEtatReseau(enLigne, false);
        suiviReseau = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { runOnUiThread(() -> changerEtat()); }
            @Override public void onLost(Network n) { runOnUiThread(() -> changerEtat()); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { runOnUiThread(() -> changerEtat()); }
        };
        try {
            reseau.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), suiviReseau);
        } catch (Exception e) {
            suiviReseau = null;
        }
    }

    private void changerEtat() {
        boolean maintenant = connecte();
        if (maintenant == enLigne) return;
        enLigne = maintenant;
        appliquerEtatReseau(maintenant, true);
    }

    /** Wi-Fi ou données mobiles actifs (le serveur local n'a pas besoin d'Internet : on ne teste pas « validé »). */
    private boolean connecte() {
        if (reseau == null) return true;
        Network actif = reseau.getActiveNetwork();
        if (actif == null) return false;
        NetworkCapabilities c = reseau.getNetworkCapabilities(actif);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void appliquerEtatReseau(boolean ok, boolean changement) {
        vue.setNetworkAvailable(ok);   // navigator.onLine et événements online/offline de la page
        vue.getSettings().setCacheMode(ok ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        if (!changement) return;
        if (ok) {
            if (messageHorsLigne != null) messageHorsLigne.dismiss();
            Toast.makeText(this, "Connexion rétablie", Toast.LENGTH_SHORT).show();
            if (pageDuCache) {
                pageDuCache = false;
                essaiCache = null;
                vue.reload();   // données à jour
            }
        } else {
            afficherHorsLigne(null);
        }
    }

    /** Message par-dessus la page affichée : pas de connexion (ou serveur injoignable si le réseau est là). */
    private void afficherHorsLigne(String detail) {
        if (isFinishing()) return;
        if (messageHorsLigne != null && messageHorsLigne.isShowing()) return;
        boolean sansReseau = !connecte();
        String titre = sansReseau ? "Vous n'êtes pas connecté à Internet" : "Serveur injoignable";
        String texte = sansReseau
            ? "Le téléphone n'a ni Wi-Fi ni données mobiles. La page affichée est la dernière chargée : elle peut ne pas "
              + "être à jour, et rien ne peut être enregistré avant le retour de la connexion."
            : "Le serveur ne répond pas" + (detail == null ? "" : " (" + detail + ")") + ". Vérifiez que le téléphone "
              + "est sur le réseau de l'entreprise et que le serveur est allumé. La page affichée peut ne pas être à jour.";
        messageHorsLigne = new AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(texte)
            .setPositiveButton("Réessayer", (d, i) -> {
                essaiCache = null;
                if (connecte()) vue.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                vue.reload();
            })
            .setNegativeButton("Fermer", null)
            .create();
        messageHorsLigne.show();
    }

    @Override
    protected void onNewIntent(Intent intention) {
        super.onNewIntent(intention);
        setIntent(intention);
        // Retour dans l'application par son icône (ou la liste des applications récentes) : la page affichée reste
        // telle quelle. Seuls le raccourci « Changer de serveur » et une notification (note à ouvrir) changent de page.
        boolean configurer = ACTION_CONFIGURER.equals(intention.getAction());
        if (!configurer && intention.getStringExtra(EXTRA_LIEN) == null && vue.getUrl() != null) return;
        ouvrir(intention);
    }

    @Override
    protected void onSaveInstanceState(Bundle etat) {
        super.onSaveInstanceState(etat);
        vue.saveState(etat);
    }

    @Override
    protected void onResume() {
        super.onResume();
        vue.onResume();
    }

    @Override
    protected void onPause() {
        retenirPage();
        // En quittant l'application : les widgets reprennent les données à jour (note validée, payée…).
        if (preferences.getString(Widgets.CLE_JETON, null) != null) Widgets.rafraichir(this);
        vue.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        String url = vue.getUrl();
        if (url != null && url.startsWith(PAGE_CONFIGURATION) && serveur() != null) {
            chargerServeur(null);
            return;
        }
        if (vue.canGoBack() && (url == null || !url.startsWith(PAGE_ERREUR))) {
            vue.goBack();
            return;
        }
        super.onBackPressed();
    }

    /** Intention de démarrage : configuration (raccourci), note d'une notification, ou accueil. */
    private void ouvrir(Intent intention) {
        if (intention != null && ACTION_CONFIGURER.equals(intention.getAction())) {
            vue.loadUrl(PAGE_CONFIGURATION);
            return;
        }
        if (serveur() == null) {
            vue.loadUrl(PAGE_CONFIGURATION);
            return;
        }
        String lien = intention == null ? null : intention.getStringExtra(EXTRA_LIEN);
        if (lien == null && intention != null && Intent.ACTION_MAIN.equals(intention.getAction())) {
            // Application fermée par Android pendant qu'on était dans une autre (photo, WhatsApp…) : on reprend la page.
            lien = preferences.getString(CLE_DERNIERE_PAGE, null);
        }
        chargerServeur(lien);
    }

    /** Retient le chemin de la page du serveur affichée (sans la page de connexion ni celles de l'application). */
    private void retenirPage() {
        String url = vue == null ? null : vue.getUrl();
        String origine = origine();
        if (url == null || origine == null || !url.startsWith(origine + "/")) return;
        String chemin = url.substring(origine.length());
        if (chemin.startsWith("/login") || chemin.startsWith("/double-authentification")) return;
        preferences.edit().putString(CLE_DERNIERE_PAGE, chemin).apply();
    }

    // ---------------------------------------------------------------------
    // Serveur
    // ---------------------------------------------------------------------

    String serveur() {
        String s = preferences.getString(CLE_SERVEUR, null);
        return s == null || s.isEmpty() ? null : s;
    }

    /** Origine du serveur (schéma, hôte et port), pour reconnaître ses pages. */
    private String origine() {
        String s = serveur();
        if (s == null) return null;
        Uri u = Uri.parse(s);
        return u.getScheme() + "://" + u.getEncodedAuthority();
    }

    private void chargerServeur(String lien) {
        String s = serveur();
        if (s == null) {
            vue.loadUrl(PAGE_CONFIGURATION);
            return;
        }
        effacerHistorique = true;
        String chemin = lien != null && lien.startsWith("/") ? lien : "/";
        vue.loadUrl(s + chemin);
    }

    /**
     * Adresse saisie → adresse du serveur : schéma http par défaut, sans « / » final ni « /api » (adresse de l'API
     * copiée par erreur). « 192.168.1.10 » devient « http://192.168.1.10 ».
     */
    static String normaliser(String saisie) {
        String s = saisie == null ? "" : saisie.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.toLowerCase(Locale.ROOT).endsWith("/api")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        Uri u = Uri.parse(s);
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        return s;
    }

    /** Code de réponse HTTP d'une adresse, -1 si injoignable. */
    private static int interroger(String adresse) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(adresse).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "MBSCFinappAndroid/" + versionAppli);
            return c.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Teste l'adresse saisie : le site répond-il, et est-ce bien MBSC Finapp (/api/health) ? */
    private void tester(String saisie) {
        String base = normaliser(saisie);
        if (base == null) {
            repondreTest(false, "Adresse invalide. Exemple : 192.168.1.10", null);
            return;
        }
        boolean schemaDonne = saisie.trim().matches("(?i)^https?://.*");
        new Thread(() -> {
            String retenue = base;
            int sante = interroger(base + "/api/health");
            if (sante < 0 && !schemaDonne) {
                // Sans schéma, on a essayé http ; un serveur en ligne n'écoute souvent qu'en https.
                String https = "https://" + base.substring("http://".length());
                int s2 = interroger(https + "/api/health");
                if (s2 >= 0) {
                    retenue = https;
                    sante = s2;
                }
            }
            if (sante >= 200 && sante < 300) {
                repondreTest(true, "Serveur MBSC Finapp joignable.", retenue);
            } else if (sante < 0) {
                repondreTest(false, "Aucune réponse de " + retenue + ". Vérifiez l'adresse, le Wi-Fi du téléphone et que le serveur est allumé.", null);
            } else if (interroger(retenue + "/") >= 200 && sante == 404) {
                repondreTest(false, "Le serveur répond, mais ce n'est pas un serveur MBSC Finapp (vérifiez le port).", null);
            } else {
                repondreTest(false, "Le serveur répond avec l'erreur " + sante + " : il démarre peut-être, réessayez dans une minute.", null);
            }
        }, "test-serveur").start();
    }

    private void repondreTest(boolean ok, String message, String adresse) {
        runOnUiThread(() -> vue.evaluateJavascript("window.resultatTest && window.resultatTest(" + ok + ","
            + chaineJs(message) + "," + (adresse == null ? "null" : chaineJs(adresse)) + ")", null));
    }

    private void choisirServeur(String saisie) {
        String base = normaliser(saisie);
        if (base == null) return;
        boolean change = !base.equals(serveur());
        preferences.edit().putString(CLE_SERVEUR, base).remove(CLE_DERNIERE_PAGE).apply();
        runOnUiThread(() -> {
            if (change) {
                // Autre serveur : la session de l'ancien ne vaut rien ici, ni le jeton de ses widgets.
                vue.clearCache(false);
                Widgets.definirJeton(MainActivity.this, "", "");
            }
            chargerServeur(null);
        });
    }

    /**
     * Couleurs du thème choisi sur le site : barre d'état (couleur de marque foncée en clair, fond de page en nuit),
     * barre de navigation et fond de la vue (fond de page). Icônes sombres sur une barre claire, claires sinon.
     */
    private void appliquerTheme(String etat, String nav, boolean sombre) {
        int cEtat;
        int cNav;
        try {
            cEtat = Color.parseColor(etat);
            cNav = Color.parseColor(nav);
        } catch (IllegalArgumentException e) {
            return;
        }
        Window w = getWindow();
        w.setStatusBarColor(cEtat);
        w.setNavigationBarColor(cNav);
        vue.setBackgroundColor(cNav);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int drapeaux = w.getDecorView().getSystemUiVisibility();
            drapeaux = claire(cEtat) ? drapeaux | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : drapeaux & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                drapeaux = claire(cNav) ? drapeaux | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : drapeaux & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(drapeaux);
        }
    }

    /** Couleur claire (luminance perçue) : icônes système sombres par-dessus. */
    private static boolean claire(int c) {
        double l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
        return l > 0.6;
    }

    private void afficherErreur(String message) {
        vue.loadUrl(PAGE_ERREUR + "?m=" + Uri.encode(message));
    }

    // ---------------------------------------------------------------------
    // Vue web
    // ---------------------------------------------------------------------

    private void configurerVue() {
        WebSettings r = vue.getSettings();
        r.setJavaScriptEnabled(true);
        r.setDomStorageEnabled(true);
        r.setDatabaseEnabled(true);
        r.setAllowFileAccess(true);
        r.setAllowContentAccess(true);
        r.setMediaPlaybackRequiresUserGesture(false);
        r.setLoadWithOverviewMode(true);
        r.setUseWideViewPort(true);
        r.setBuiltInZoomControls(true);
        r.setDisplayZoomControls(false);
        r.setSupportMultipleWindows(false);
        r.setJavaScriptCanOpenWindowsAutomatically(true);
        r.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Défilement fluide : la vue prépare aussi l'image des zones juste hors de l'écran (au prix d'un peu de mémoire),
        // et son moteur de rendu reste prioritaire tant que l'application est au premier plan.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) r.setOffscreenPreRaster(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vue.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
        try {
            versionAppli = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignore) {
            // version inconnue
        }
        r.setUserAgentString(r.getUserAgentString() + " MBSCFinappAndroid/" + versionAppli);

        vue.addJavascriptInterface(new Passerelle(), "FinappAndroid");
        vue.setWebViewClient(new Client());
        vue.setWebChromeClient(new Chrome());
        vue.setDownloadListener((url, agent, disposition, type, taille) -> {
            // Téléchargement direct (rare : le site passe par des blobs, traités par passerelle.js).
            ouvrirAilleurs(url);
        });
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest requete) {
            Uri u = requete.getUrl();
            String schema = u.getScheme() == null ? "" : u.getScheme();
            if (schema.equals("file")) return false;
            String origine = origine();
            if ((schema.equals("http") || schema.equals("https")) && origine != null
                && origine.equalsIgnoreCase(schema + "://" + u.getEncodedAuthority())) {
                return false;
            }
            // Autre site, téléphone, e-mail, WhatsApp : application du téléphone.
            ouvrirAilleurs(u.toString());
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap icone) {
            super.onPageStarted(v, url, icone);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            super.onPageFinished(v, url);
            if (url != null && !url.equals(essaiCache) && !url.startsWith("file:") && !pageDuCache) essaiCache = null;
            if (effacerHistorique && url != null && !url.startsWith("file:")) {
                v.clearHistory();
                effacerHistorique = false;
            }
            String origine = origine();
            if (url != null && origine != null && url.startsWith(origine) && passerelle != null) {
                v.evaluateJavascript(passerelle, null);
            }
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest requete, WebResourceError erreur) {
            if (!requete.isForMainFrame()) return;
            String url = requete.getUrl().toString();
            if (url.startsWith("file:")) return;
            if (!url.equals(essaiCache)) {
                // Plutôt qu'une page blanche : la dernière version de la page gardée par la vue web, avec un message.
                essaiCache = url;
                pageDuCache = true;
                v.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ONLY);
                v.loadUrl(url);
                v.postDelayed(() -> {
                    if (connecte()) v.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                    else v.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                }, 1500);
                afficherHorsLigne(String.valueOf(erreur.getDescription()));
                return;
            }
            // Pas de copie en cache non plus (première ouverture hors ligne) : page d'erreur de l'application.
            essaiCache = null;
            pageDuCache = false;
            if (messageHorsLigne != null) messageHorsLigne.dismiss();
            afficherErreur(connecte()
                ? "Le serveur ne répond pas (" + erreur.getDescription() + ")."
                : "Vous n'êtes pas connecté à Internet : ni Wi-Fi ni données mobiles.");
        }

        @Override
        public void onReceivedHttpError(WebView v, WebResourceRequest requete, WebResourceResponse reponse) {
            if (!requete.isForMainFrame()) return;
            int statut = reponse.getStatusCode();
            if (statut == 502 || statut == 503 || statut == 504) {
                afficherErreur("Le serveur répond avec l'erreur " + statut + " : il redémarre peut-être, réessayez dans une minute.");
            }
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> rappel, FileChooserParams parametres) {
            if (choixFichiers != null) choixFichiers.onReceiveValue(null);
            choixFichiers = rappel;
            parametresChoix = parametres;
            boolean dejaDemande = preferences.getBoolean("camera_demandee", false);
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && !dejaDemande) {
                preferences.edit().putBoolean("camera_demandee", true).apply();
                requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_FICHIERS);
            } else {
                lancerChoixFichiers();
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest demande) {
            // Scan des justificatifs (getUserMedia) : seulement la caméra, seulement pour le serveur choisi.
            boolean video = false;
            for (String ressource : demande.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(ressource)) video = true;
            }
            String origine = origine();
            if (!video || origine == null || !demande.getOrigin().toString().startsWith(origine)) {
                demande.deny();
                return;
            }
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    demande.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    demandeCamera = demande;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_PAGE);
                }
            });
        }
    }

    // ---------------------------------------------------------------------
    // Envoi de fichiers (pièces jointes) et appareil photo
    // ---------------------------------------------------------------------

    private void lancerChoixFichiers() {
        if (choixFichiers == null || parametresChoix == null) return;
        Intent contenu = parametresChoix.createIntent();
        contenu.addCategory(Intent.CATEGORY_OPENABLE);
        if (parametresChoix.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            contenu.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        Intent choix = Intent.createChooser(contenu, "Joindre un fichier");
        photoEnCours = null;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            File photo = new File(Fichiers.dossier(this), "Photo_" + horodatage() + ".jpg");
            Uri adresse = Fichiers.adresse(photo);
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, adresse);
            camera.setClipData(ClipData.newRawUri("", adresse));
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            choix.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            photoEnCours = photo;
        }
        try {
            startActivityForResult(choix, DEMANDE_FICHIERS);
        } catch (ActivityNotFoundException e) {
            choixFichiers.onReceiveValue(null);
            choixFichiers = null;
            message("Aucune application ne permet de choisir un fichier.");
        }
    }

    @Override
    protected void onActivityResult(int demande, int resultat, Intent donnees) {
        super.onActivityResult(demande, resultat, donnees);
        // QR de l'adresse du serveur (écran de configuration, 26/11/2026).
        IntentResult scan = IntentIntegrator.parseActivityResult(demande, resultat, donnees);
        if (scan != null) {
            if (scan.getContents() != null && !scan.getContents().trim().isEmpty()) {
                vue.evaluateJavascript("window.adresseScannee && window.adresseScannee("
                    + chaineJs(scan.getContents().trim()) + ")", null);
            }
            return;
        }
        if (demande != DEMANDE_FICHIERS || choixFichiers == null) return;
        Uri[] choisis = null;
        if (resultat == RESULT_OK) {
            if (donnees != null && donnees.getClipData() != null) {
                ClipData clip = donnees.getClipData();
                choisis = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) choisis[i] = clip.getItemAt(i).getUri();
            } else if (donnees != null && donnees.getData() != null) {
                choisis = new Uri[]{donnees.getData()};
            } else if (photoEnCours != null && photoEnCours.length() > 0) {
                choisis = new Uri[]{Fichiers.adresse(photoEnCours)};
            }
        }
        choixFichiers.onReceiveValue(choisis);
        choixFichiers = null;
        parametresChoix = null;
    }

    @Override
    public void onRequestPermissionsResult(int demande, String[] permissions, int[] resultats) {
        boolean accordee = resultats.length > 0 && resultats[0] == PackageManager.PERMISSION_GRANTED;
        if (demande == PERMISSION_CAMERA_SCAN) {
            if (accordee) lancerScanServeur();
            else message("Le scanner a besoin de l'appareil photo : autorisez-le dans les réglages de l'application.");
        } else if (demande == PERMISSION_CAMERA_FICHIERS) {
            lancerChoixFichiers();
        } else if (demande == PERMISSION_CAMERA_PAGE && demandeCamera != null) {
            if (accordee) demandeCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else demandeCamera.deny();
            demandeCamera = null;
        }
    }

    /**
     * Scanner du QR de l'adresse du serveur, affiché sur la page « À propos » du site : l'adresse n'est plus à saisir
     * au premier lancement.
     */
    private void scannerServeur() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_SCAN);
            return;
        }
        lancerScanServeur();
    }

    private void lancerScanServeur() {
        IntentIntegrator scan = new IntentIntegrator(this);
        scan.setCaptureActivity(Scanner.class);
        scan.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scan.setPrompt("Visez le QR du serveur (page « À propos » du site)");
        scan.setBeepEnabled(true);
        scan.setOrientationLocked(true);
        scan.initiateScan();
    }

    private void demanderNotifications() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !preferences.getBoolean("notifications_demandees", false)) {
            preferences.edit().putBoolean("notifications_demandees", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_NOTIFICATIONS);
        }
    }

    // ---------------------------------------------------------------------
    // Fichiers reçus du site (PDF, Excel)
    // ---------------------------------------------------------------------

    private void enregistrer(String base64, String nom, String type) {
        byte[] octets;
        try {
            octets = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            message("Fichier illisible.");
            return;
        }
        String mime = type == null || type.isEmpty() || type.equals("application/octet-stream") ? Fichiers.typeMime(nettoyer(nom)) : type;
        String fichierNom = nomDeFichier(nom, mime);
        File copie = new File(Fichiers.dossier(this), fichierNom);
        try (OutputStream sortie = new FileOutputStream(copie)) {
            sortie.write(octets);
        } catch (Exception e) {
            message("Enregistrement impossible : " + e.getMessage());
            return;
        }
        boolean dansTelechargements = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues valeurs = new ContentValues();
                valeurs.put(MediaStore.Downloads.DISPLAY_NAME, fichierNom);
                valeurs.put(MediaStore.Downloads.MIME_TYPE, mime);
                valeurs.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MBSC Finapp");
                Uri cible = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valeurs);
                if (cible != null) {
                    try (OutputStream sortie = getContentResolver().openOutputStream(cible)) {
                        if (sortie != null) {
                            sortie.write(octets);
                            dansTelechargements = true;
                        }
                    }
                }
            } catch (Exception ignore) {
                // la copie dans le cache suffit pour l'ouvrir
            }
        }
        final boolean garde = dansTelechargements;
        runOnUiThread(() -> {
            if (garde) message("Enregistré dans Téléchargements/MBSC Finapp : " + fichierNom);
            Intent voir = new Intent(Intent.ACTION_VIEW);
            voir.setDataAndType(Fichiers.adresse(copie), mime);
            voir.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(Intent.createChooser(voir, "Ouvrir " + fichierNom).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                message("Aucune application pour ouvrir " + fichierNom + (garde ? " (il est dans Téléchargements)." : "."));
            }
        });
    }

    private static String nettoyer(String nom) {
        if (nom == null) return "";
        return nom.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
    }

    private static String nomDeFichier(String nom, String mime) {
        String n = nettoyer(nom);
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (n.isEmpty()) n = "Document_" + horodatage();
        if (extension != null && !n.contains(".")) n = n + "." + extension;
        return n;
    }

    private static String horodatage() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date());
    }

    // ---------------------------------------------------------------------
    // Divers
    // ---------------------------------------------------------------------

    private void imprimer(String titre) {
        runOnUiThread(() -> {
            PrintManager impression = (PrintManager) getSystemService(PRINT_SERVICE);
            if (impression == null) return;
            String nom = titre == null || titre.isEmpty() ? "MBSC Finapp" : titre;
            impression.print(nom, vue.createPrintDocumentAdapter(nom),
                new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
        });
    }

    private void ouvrirAilleurs(String url) {
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                message("Aucune application pour ouvrir ce lien.");
            }
        });
    }

    void message(String texte) {
        runOnUiThread(() -> Toast.makeText(this, texte, Toast.LENGTH_LONG).show());
    }

    private String lireAsset(String nom) {
        try (InputStream entree = getAssets().open(nom); ByteArrayOutputStream tampon = new ByteArrayOutputStream()) {
            byte[] bloc = new byte[8192];
            int lus;
            while ((lus = entree.read(bloc)) > 0) tampon.write(bloc, 0, lus);
            return tampon.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    private static String chaineJs(String texte) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : texte.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '<': b.append("\\u003c"); break;
                default: b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /**
     * Ce que les pages voient de l'application (window.FinappAndroid). Appelé hors du fil de l'interface : tout ce qui
     * touche la vue repasse par runOnUiThread.
     */
    private class Passerelle {
        @JavascriptInterface public String version() { return versionAppli; }
        @JavascriptInterface public String serveurActuel() { String s = serveur(); return s == null ? "" : s; }
        @JavascriptInterface public void tester(String adresse) { MainActivity.this.tester(adresse); }
        @JavascriptInterface public void choisirServeur(String adresse) { MainActivity.this.choisirServeur(adresse); }
        @JavascriptInterface public void configurer() { runOnUiThread(() -> vue.loadUrl(PAGE_CONFIGURATION)); }
        /**
         * Pose un widget sur l'écran d'accueil depuis l'application (Android 8+, si le lanceur l'accepte) : utile quand
         * le lanceur ne liste pas les widgets des applications installées à la main (HiOS, Android Go…).
         * @return "ok" (le téléphone demande confirmation), "non-supporte" ou "erreur".
         */
        @JavascriptInterface public String ajouterWidget(String type) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "non-supporte";
            try {
                android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(MainActivity.this);
                if (!m.isRequestPinAppWidgetSupported()) return "non-supporte";
                Class<?> fournisseur = "tresorerie".equals(type) ? WidgetTresorerie.class : WidgetNotes.class;
                boolean ok = m.requestPinAppWidget(new android.content.ComponentName(MainActivity.this, fournisseur), null, null);
                if (ok) Widgets.rafraichir(MainActivity.this);
                return ok ? "ok" : "non-supporte";
            } catch (Exception e) {
                return "erreur";
            }
        }
        @JavascriptInterface public String jetonWidget() { return preferences.getString(Widgets.CLE_JETON, ""); }
        @JavascriptInterface public String utilisateurWidget() { return preferences.getString(Widgets.CLE_UTILISATEUR, ""); }
        @JavascriptInterface public void definirJetonWidget(String jeton, String utilisateur) {
            Widgets.definirJeton(MainActivity.this, jeton, utilisateur);
        }
        @JavascriptInterface public void theme(String etat, String nav, boolean sombre) {
            preferences.edit().putString(CLE_THEME_ETAT, etat).putString(CLE_THEME_NAV, nav)
                .putBoolean(CLE_THEME_SOMBRE, sombre).apply();
            runOnUiThread(() -> appliquerTheme(etat, nav, sombre));
        }
        @JavascriptInterface public void reessayer() { runOnUiThread(() -> chargerServeur(null)); }
        @JavascriptInterface public void enregistrer(String base64, String nom, String type) { MainActivity.this.enregistrer(base64, nom, type); }
        @JavascriptInterface public void imprimer(String titre) { MainActivity.this.imprimer(titre); }
        @JavascriptInterface public void ouvrirLien(String url) { ouvrirAilleurs(url); }
        @JavascriptInterface public void message(String texte) { MainActivity.this.message(texte); }
        /** Jeton Firebase du téléphone (vide tant que Firebase ne l'a pas donné) : le site l'inscrit au compte connecté. */
        @JavascriptInterface public void scannerServeur() { runOnUiThread(MainActivity.this::scannerServeur); }
        @JavascriptInterface public String jetonPush() { return Notifications.jeton(MainActivity.this); }
    }
}

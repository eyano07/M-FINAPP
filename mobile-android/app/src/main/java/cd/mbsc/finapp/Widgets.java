package cd.mbsc.finapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Widgets de l'écran d'accueil (18/11/2026) : « Notes de frais » et « Trésorerie ».
 * <p>
 * Ils lisent {@code /api/widget/resume} avec le jeton du téléphone (en-tête X-Jeton-Widget), remis par le site à la
 * connexion dans l'application ({@code FinappAndroid.definirJetonWidget}) et effacé à la déconnexion. Le dernier
 * résumé reçu est gardé : sans réseau, le widget montre les dernières données avec leur heure.
 */
final class Widgets {

    static final String CLE_JETON = "widget_jeton";
    static final String CLE_UTILISATEUR = "widget_utilisateur";
    static final String CLE_RESUME = "widget_resume";
    static final String CLE_ERREUR = "widget_erreur";
    static final String ACTION_RAFRAICHIR = "cd.mbsc.finapp.WIDGET_RAFRAICHIR";

    private static final int LIGNES = 4;
    private static final int CANAUX = 3;
    private static final int[] LIGNE = {R.id.ligne1, R.id.ligne2, R.id.ligne3, R.id.ligne4};
    private static final int[] LIGNE_HAUT = {R.id.ligne1_haut, R.id.ligne2_haut, R.id.ligne3_haut, R.id.ligne4_haut};
    private static final int[] LIGNE_BAS = {R.id.ligne1_bas, R.id.ligne2_bas, R.id.ligne3_bas, R.id.ligne4_bas};
    private static final int[][] SEGMENTS = {
        {R.id.seg1_1, R.id.seg1_2, R.id.seg1_3, R.id.seg1_4},
        {R.id.seg2_1, R.id.seg2_2, R.id.seg2_3, R.id.seg2_4},
        {R.id.seg3_1, R.id.seg3_2, R.id.seg3_3, R.id.seg3_4},
        {R.id.seg4_1, R.id.seg4_2, R.id.seg4_3, R.id.seg4_4},
    };
    private static final int[] CANAL = {R.id.canal1, R.id.canal2, R.id.canal3};
    private static final int[] CANAL_NOM = {R.id.canal1_nom, R.id.canal2_nom, R.id.canal3_nom};
    private static final int[] CANAL_SOLDE = {R.id.canal1_solde, R.id.canal2_solde, R.id.canal3_solde};

    private Widgets() {}

    static SharedPreferences preferences(Context c) {
        return c.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Jeton remis par le site (vide : déconnexion) ; les widgets se mettent à jour aussitôt. */
    static void definirJeton(Context c, String jeton, String utilisateur) {
        SharedPreferences.Editor e = preferences(c).edit();
        if (jeton == null || jeton.isEmpty()) {
            e.remove(CLE_JETON).remove(CLE_UTILISATEUR).remove(CLE_RESUME).remove(CLE_ERREUR);
            Notifications.badge(c, 0);   // déconnexion : plus de pastille
        } else {
            e.putString(CLE_JETON, jeton).putString(CLE_UTILISATEUR, utilisateur == null ? "" : utilisateur);
        }
        e.apply();
        rafraichir(c);
    }

    /** Relit le résumé et redessine tous les widgets posés (sortie de l'application, notification reçue…). */
    static void rafraichir(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            telecharger(app);
            dessinerTout(app);
        }, "widgets").start();
    }

    /** Télécharge le résumé (fil d'arrière-plan). */
    static void telecharger(Context c) {
        SharedPreferences p = preferences(c);
        String serveur = p.getString(MainActivity.CLE_SERVEUR, null);
        String jeton = p.getString(CLE_JETON, null);
        if (serveur == null || jeton == null) return;
        HttpURLConnection cx = null;
        try {
            cx = (HttpURLConnection) new URL(serveur + "/api/widget/resume").openConnection();
            cx.setConnectTimeout(8000);
            cx.setReadTimeout(12000);
            cx.setRequestProperty("X-Jeton-Widget", jeton);
            cx.setRequestProperty("Accept", "application/json");
            cx.setRequestProperty("User-Agent", "MBSCFinappAndroid/widget");
            int code = cx.getResponseCode();
            if (code == 200) {
                String corps = lire(cx.getInputStream());
                p.edit().putString(CLE_RESUME, corps).remove(CLE_ERREUR).apply();
                try {
                    Notifications.badge(c, new JSONObject(corps).optInt("notificationsNonLues", 0));
                } catch (Exception ignore) {
                    // résumé sans le nombre (ancien serveur)
                }
            } else if (code == 401 || code == 403) {
                // Jeton retiré (déconnexion ailleurs, compte désactivé) : plus rien à montrer.
                p.edit().remove(CLE_RESUME).putString(CLE_ERREUR, "Ouvrez l'application pour vous connecter.").apply();
            } else {
                p.edit().putString(CLE_ERREUR, "Serveur indisponible (" + code + ").").apply();
            }
        } catch (Exception e) {
            p.edit().putString(CLE_ERREUR, "Hors connexion.").apply();
        } finally {
            if (cx != null) cx.disconnect();
        }
    }

    private static String lire(InputStream in) throws Exception {
        try (InputStream flux = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] tampon = new byte[8192];
            int n;
            while ((n = flux.read(tampon)) > 0) out.write(tampon, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void dessinerTout(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, WidgetNotes.class))) m.updateAppWidget(id, notes(c));
        for (int id : m.getAppWidgetIds(new ComponentName(c, WidgetTresorerie.class))) m.updateAppWidget(id, tresorerie(c));
    }

    // ── Dessin ───────────────────────────────────────────────────────────────────

    private static JSONObject resume(Context c) {
        String brut = preferences(c).getString(CLE_RESUME, null);
        if (brut == null) return null;
        try {
            return new JSONObject(brut);
        } catch (Exception e) {
            return null;
        }
    }

    /** Message quand il n'y a rien à montrer : pas de serveur, pas connecté, ou erreur sans données gardées. */
    private static String absence(Context c) {
        SharedPreferences p = preferences(c);
        if (p.getString(MainActivity.CLE_SERVEUR, null) == null) return "Ouvrez l'application pour choisir le serveur.";
        if (p.getString(CLE_JETON, null) == null) return "Ouvrez l'application et connectez-vous.";
        String erreur = p.getString(CLE_ERREUR, null);
        return erreur != null ? erreur : "Chargement…";
    }

    static RemoteViews notes(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_notes);
        v.setOnClickPendingIntent(R.id.racine, ouvrir(c, "/notes-frais", 100));
        v.setOnClickPendingIntent(R.id.rafraichir, actualiser(c, WidgetNotes.class, 101));
        for (int i = 0; i < LIGNES; i++) v.setViewVisibility(LIGNE[i], View.GONE);
        JSONObject r = resume(c);
        if (r == null) {
            v.setViewVisibility(R.id.compteur, View.GONE);
            v.setTextViewText(R.id.resume, "");
            montrerMessage(v, absence(c));
            v.setTextViewText(R.id.maj, "");
            return v;
        }
        JSONObject n = r.optJSONObject("notes");
        int aTraiter = n == null ? 0 : n.optInt("aTraiter");
        int enCours = n == null ? 0 : n.optInt("mesEnCours");
        int rejetees = n == null ? 0 : n.optInt("mesRejetees");
        v.setViewVisibility(R.id.compteur, aTraiter > 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.compteur, String.valueOf(aTraiter));
        StringBuilder s = new StringBuilder();
        s.append(aTraiter == 0 ? "Rien à traiter" : aTraiter + " à traiter");
        if (enCours > 0) s.append(" · ").append(enCours).append(" en cours");
        if (rejetees > 0) s.append(" · ").append(rejetees).append(rejetees > 1 ? " rejetées" : " rejetée");
        v.setTextViewText(R.id.resume, s.toString());

        JSONArray lignes = n == null ? null : n.optJSONArray("lignes");
        int montrees = 0;
        for (int i = 0; lignes != null && i < lignes.length() && montrees < LIGNES; i++) {
            JSONObject l = lignes.optJSONObject(i);
            if (l == null) continue;
            String objet = l.optString("objet", "");
            v.setViewVisibility(LIGNE[montrees], View.VISIBLE);
            v.setTextViewText(LIGNE_HAUT[montrees], l.optString("reference", "") + (objet.isEmpty() ? "" : " · " + objet));
            String bas = l.optString("etat", "") + " · " + montant(l.opt("montant"), l.optString("devise", ""));
            v.setTextViewText(LIGNE_BAS[montrees], bas);
            v.setTextColor(LIGNE_BAS[montrees], c.getColor(l.optBoolean("aTraiter") ? R.color.widget_alerte : R.color.widget_doux));
            int[] couleurs = pipeline(l.optString("statut", ""));
            for (int k = 0; k < 4; k++) v.setInt(SEGMENTS[montrees][k], "setColorFilter", c.getColor(couleurs[k]));
            long id = l.optLong("id");
            v.setOnClickPendingIntent(LIGNE[montrees], ouvrir(c, "/notes-frais/" + id, 200 + montrees));
            montrees++;
        }
        if (montrees == 0) montrerMessage(v, "Aucune note en cours. Touchez pour ouvrir les notes de frais.");
        else v.setViewVisibility(R.id.message, View.GONE);
        v.setTextViewText(R.id.maj, horodatage(c, r));
        return v;
    }

    static RemoteViews tresorerie(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_tresorerie);
        v.setOnClickPendingIntent(R.id.racine, ouvrir(c, "/dashboard", 300));
        v.setOnClickPendingIntent(R.id.rafraichir, actualiser(c, WidgetTresorerie.class, 301));
        for (int i = 0; i < CANAUX; i++) v.setViewVisibility(CANAL[i], View.GONE);
        v.setViewVisibility(R.id.total, View.GONE);
        v.setViewVisibility(R.id.total_libelle, View.GONE);
        v.setViewVisibility(R.id.jour, View.GONE);
        v.setViewVisibility(R.id.resultat, View.GONE);
        JSONObject r = resume(c);
        if (r == null) {
            montrerMessage(v, absence(c));
            v.setTextViewText(R.id.maj, "");
            return v;
        }
        JSONObject t = r.optJSONObject("tresorerie");
        if (t == null) {
            String m = r.optString("tresorerieMessage", "");
            montrerMessage(v, m.isEmpty() || "null".equals(m) ? "Trésorerie non disponible." : m);
            v.setTextViewText(R.id.maj, horodatage(c, r));
            return v;
        }
        String devise = t.optString("devise", "USD");
        v.setViewVisibility(R.id.total, View.VISIBLE);
        v.setViewVisibility(R.id.total_libelle, View.VISIBLE);
        v.setTextViewText(R.id.total, montant(t.opt("total"), devise));
        v.setTextViewText(R.id.total_libelle, "Solde total de la trésorerie");
        v.setViewVisibility(R.id.jour, View.VISIBLE);
        v.setTextViewText(R.id.entrees, "↓ Entrées du jour\n+" + montant(t.opt("entreesJour"), devise));
        v.setTextViewText(R.id.sorties, "Sorties du jour ↑\n−" + montant(t.opt("sortiesJour"), devise));
        JSONArray canaux = t.optJSONArray("canaux");
        for (int i = 0; canaux != null && i < canaux.length() && i < CANAUX; i++) {
            JSONObject k = canaux.optJSONObject(i);
            if (k == null) continue;
            v.setViewVisibility(CANAL[i], View.VISIBLE);
            v.setTextViewText(CANAL_NOM[i], k.optString("nom", ""));
            v.setTextViewText(CANAL_SOLDE[i], montant(k.opt("solde"), devise));
        }
        // Résultat net de l'exercice : envoyé par le serveur au DFIN, au DA et à l'administrateur seulement.
        if (!t.isNull("resultatNet")) {
            double resultat = t.optDouble("resultatNet", 0);
            v.setViewVisibility(R.id.resultat, View.VISIBLE);
            v.setTextViewText(R.id.resultat_nom, t.optString("resultatLibelle", "Résultat net"));
            v.setTextViewText(R.id.resultat_solde, (resultat > 0 ? "+" : "") + montant(t.opt("resultatNet"), devise));
            v.setTextColor(R.id.resultat_solde, c.getColor(resultat < 0 ? R.color.widget_alerte : R.color.widget_ok));
        }
        v.setViewVisibility(R.id.message, View.GONE);
        v.setTextViewText(R.id.maj, horodatage(c, r));
        return v;
    }

    /**
     * Couleurs des 4 étapes d'une note (DFIN · DA · Caisse · Payée), d'après son état réel : bleu = vérifiée par le DFIN,
     * vert = validée par le DA, orange = étape en cours (la note y attend), vert foncé = payée, rouge = rejetée, gris = pas
     * encore atteinte. « Payée » ne se colore qu'une fois la note payée : transmise à la caisse, c'est la case Caisse qui
     * est en cours (comme la barre du tableau de bord du site).
     */
    static int[] pipeline(String statut) {
        int g = R.color.etape_avenir, o = R.color.etape_attente, b = R.color.etape_dfin, v = R.color.etape_validee;
        int r = R.color.etape_rejetee, p = R.color.etape_payee;
        switch (statut) {
            case "SOUMISE": return new int[]{o, g, g, g};
            case "VERIFIEE_DFIN": return new int[]{b, o, g, g};
            case "REJETEE_DA": return new int[]{b, r, g, g};
            case "VALIDEE_DA": return new int[]{b, v, g, g};      // validée, pas encore transmise à la caisse
            case "TRANSMISE_CAISSE": return new int[]{b, v, o, g};  // à la caisse, pas encore payée
            case "PAYEE": return new int[]{b, v, v, p};
            case "ANNULEE": return new int[]{r, r, r, r};
            default: return new int[]{g, g, g, g};   // brouillon : pas encore soumise
        }
    }

    private static void montrerMessage(RemoteViews v, String texte) {
        v.setViewVisibility(R.id.message, View.VISIBLE);
        v.setTextViewText(R.id.message, texte);
    }

    /** « Mis à jour à 18:30 », plus la raison si la dernière lecture a échoué (données gardées). */
    private static String horodatage(Context c, JSONObject r) {
        String erreur = preferences(c).getString(CLE_ERREUR, null);
        String heure = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.FRANCE).format(new Date(lu(r)));
        return (erreur != null ? erreur + " " : "") + "Mis à jour à " + heure;
    }

    private static long lu(JSONObject r) {
        try {
            return java.time.Instant.parse(r.optString("genereLe")).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    static String montant(Object valeur, String devise) {
        BigDecimal m;
        try {
            m = valeur == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(valeur));
        } catch (NumberFormatException e) {
            m = BigDecimal.ZERO;
        }
        NumberFormat f = NumberFormat.getNumberInstance(Locale.FRANCE);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        String d = devise == null || devise.isEmpty() || "null".equals(devise) ? "" : " " + ("CDF".equals(devise) ? "FC" : devise);
        return f.format(m) + d;
    }

    // ── Gestes ───────────────────────────────────────────────────────────────────

    /** Ouvre l'application sur une page du serveur (même chemin que les notifications). */
    private static PendingIntent ouvrir(Context c, String lien, int code) {
        Intent i = new Intent(c, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_LIEN, lien)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent actualiser(Context c, Class<?> fournisseur, int code) {
        Intent i = new Intent(c, fournisseur).setAction(ACTION_RAFRAICHIR);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

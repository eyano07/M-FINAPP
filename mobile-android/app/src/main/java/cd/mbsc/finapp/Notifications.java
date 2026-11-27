package cd.mbsc.finapp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessaging;

/** Canal, jeton Firebase et affichage des notifications push. */
final class Notifications {

    static final String CANAL = "notes_frais";
    /** Budgets (2.15) : à approuver, approuvés, rejetés, mis en exécution, dépassés. Canal à part, réglable seul. */
    static final String CANAL_BUDGETS = "budgets";
    private static final String CLE_JETON = "jeton_push";

    private Notifications() {
    }

    static void creerCanal(Context contexte) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel canal = new NotificationChannel(CANAL, "Notes de frais", NotificationManager.IMPORTANCE_HIGH);
        canal.setDescription("Notes à vérifier ou à approuver, décisions sur vos notes");
        canal.setShowBadge(true);   // pastille sur l'icône de l'application
        NotificationChannel budgets = new NotificationChannel(CANAL_BUDGETS, "Budgets", NotificationManager.IMPORTANCE_HIGH);
        budgets.setDescription("Budgets à approuver ou à mettre en exécution, décisions et dépassements budgétaires");
        budgets.setShowBadge(true);
        NotificationManager gestionnaire = contexte.getSystemService(NotificationManager.class);
        if (gestionnaire != null) {
            gestionnaire.createNotificationChannel(canal);
            gestionnaire.createNotificationChannel(budgets);
        }
    }

    /** Jeton connu (vide si Firebase ne l'a pas encore donné). */
    static String jeton(Context contexte) {
        return contexte.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE).getString(CLE_JETON, "");
    }

    static void retenirJeton(Context contexte, String jeton) {
        contexte.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE).edit().putString(CLE_JETON, jeton).apply();
    }

    /** Demande le jeton à Firebase (il change rarement : réinstallation, données effacées). */
    static void rafraichirJeton(Context contexte) {
        try {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(j -> {
                if (j != null && !j.isEmpty()) retenirJeton(contexte, j);
            });
        } catch (Exception ignore) {
            // Firebase indisponible (pas de services Google sur le téléphone) : pas de notifications push.
        }
    }

    static final String CLE_BADGE = "badge";

    /**
     * Pastille rouge sur l'icône de l'application (comme WhatsApp) : le nombre de notifications non lues du compte.
     * Android l'affiche d'après les notifications présentes (setNumber) ; beaucoup de lanceurs (HiOS, XOS, Samsung,
     * Huawei, LG, Sony…) écoutent aussi le message BADGE_COUNT_UPDATE. À 0, les notifications lues sont retirées.
     */
    static void badge(Context contexte, int nombre) {
        int n = Math.max(0, nombre);
        contexte.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE).edit().putInt(CLE_BADGE, n).apply();
        try {
            Intent i = new Intent("android.intent.action.BADGE_COUNT_UPDATE")
                .putExtra("badge_count", n)
                .putExtra("badge_count_package_name", contexte.getPackageName())
                .putExtra("badge_count_class_name", MainActivity.class.getName());
            contexte.sendBroadcast(i);
        } catch (Exception ignore) {
            // lanceur sans pastille numérotée
        }
        if (n == 0) {
            NotificationManager g = (NotificationManager) contexte.getSystemService(Context.NOTIFICATION_SERVICE);
            if (g != null) g.cancelAll();
        }
    }

    static int badge(Context contexte) {
        return contexte.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE).getInt(CLE_BADGE, 0);
    }

    /** Canal d'une notification d'après la page qu'elle ouvre : les budgets à part, le reste avec les notes de frais. */
    static String canalDe(String lien) {
        return lien != null && (lien.equals("/budgets") || lien.startsWith("/budgets/") || lien.startsWith("/budgets?"))
            ? CANAL_BUDGETS : CANAL;
    }

    static void afficher(Context contexte, String titre, String message, String lien, int numero) {
        creerCanal(contexte);   // notification reçue avant la première ouverture de cette version
        // Une notification de plus en attendant le vrai nombre (relu aussitôt par Widgets.rafraichir).
        int nombre = badge(contexte) + 1;
        badge(contexte, nombre);
        Intent ouvrir = new Intent(contexte, MainActivity.class)
            .putExtra(MainActivity.EXTRA_LIEN, lien)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent action = PendingIntent.getActivity(contexte, numero, ouvrir,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder n = Build.VERSION.SDK_INT >= 26
            ? new android.app.Notification.Builder(contexte, canalDe(lien))
            : new android.app.Notification.Builder(contexte);
        n.setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0F6E56)
            .setContentTitle(titre)
            .setContentText(message)
            .setStyle(new android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setNumber(nombre)
            .setContentIntent(action);
        if (Build.VERSION.SDK_INT < 26) n.setPriority(android.app.Notification.PRIORITY_HIGH);
        NotificationManager gestionnaire = (NotificationManager) contexte.getSystemService(Context.NOTIFICATION_SERVICE);
        if (gestionnaire != null) gestionnaire.notify(numero, n.build());
    }
}

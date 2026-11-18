package cd.mbsc.finapp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/** Widget « Notes de frais » de l'écran d'accueil : voir {@link Widgets}. */
public class WidgetNotes extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        // Dernières données gardées tout de suite, puis lecture du serveur en arrière-plan.
        for (int id : ids) m.updateAppWidget(id, Widgets.notes(c));
        PendingResult fin = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Widgets.telecharger(app);
                Widgets.dessinerTout(app);
            } finally {
                fin.finish();
            }
        }, "widget-Notes").start();
    }

    @Override
    public void onReceive(Context c, Intent intention) {
        super.onReceive(c, intention);
        if (Widgets.ACTION_RAFRAICHIR.equals(intention.getAction())) {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            onUpdate(c, m, m.getAppWidgetIds(new android.content.ComponentName(c, WidgetNotes.class)));
        }
    }
}

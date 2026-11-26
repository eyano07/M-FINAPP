package cd.mbsc.finapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Sert les fichiers du dossier cache/fichiers aux autres applications (lecteur PDF, Excel, appareil photo), comme le
 * FileProvider d'androidx, sans dépendance. Une adresse est content://cd.mbsc.finapp.fichiers/&lt;nom&gt; : un nom simple,
 * jamais un chemin, pour qu'on ne puisse rien lire en dehors de ce dossier. Le fournisseur n'est pas exporté : une autre
 * application n'y accède que par la permission temporaire accordée sur une adresse précise.
 */
public class Fichiers extends ContentProvider {

    static final String AUTORITE = "cd.mbsc.finapp.fichiers";

    static File dossier(Context contexte) {
        File d = new File(contexte.getCacheDir(), "fichiers");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static Uri adresse(File fichier) {
        return new Uri.Builder().scheme("content").authority(AUTORITE).appendPath(fichier.getName()).build();
    }

    static String typeMime(String nom) {
        int point = nom.lastIndexOf('.');
        if (point < 0) return "application/octet-stream";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(nom.substring(point + 1).toLowerCase());
        return type != null ? type : "application/octet-stream";
    }

    private File fichier(Uri uri) throws FileNotFoundException {
        String nom = uri.getLastPathSegment();
        if (nom == null || nom.isEmpty() || nom.contains("/") || nom.contains("..")) throw new FileNotFoundException(String.valueOf(uri));
        return new File(dossier(getContext()), nom);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fichier(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String tri) {
        File f;
        try {
            f = fichier(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] colonnes = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor curseur = new MatrixCursor(colonnes, 1);
        Object[] ligne = new Object[colonnes.length];
        for (int i = 0; i < colonnes.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(colonnes[i])) ligne[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(colonnes[i])) ligne[i] = f.length();
        }
        curseur.addRow(ligne);
        return curseur;
    }

    @Override
    public String getType(Uri uri) {
        String nom = uri.getLastPathSegment();
        return nom == null ? null : typeMime(nom);
    }

    @Override
    public Uri insert(Uri uri, ContentValues valeurs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues valeurs, String selection, String[] args) {
        return 0;
    }
}

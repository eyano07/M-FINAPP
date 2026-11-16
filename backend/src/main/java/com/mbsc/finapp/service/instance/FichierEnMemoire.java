package com.mbsc.finapp.service.instance;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Piece jointe recue d'un autre serveur, presentee comme un fichier televerse : la nouvelle note creee par le serveur
 * local suit exactement le meme chemin qu'au guichet (controles de type et de taille compris).
 */
public class FichierEnMemoire implements MultipartFile {

    private final String nom;
    private final String typeMime;
    private final byte[] contenu;

    public FichierEnMemoire(String nom, String typeMime, byte[] contenu) {
        this.nom = nom;
        this.typeMime = typeMime;
        this.contenu = contenu;
    }

    @Override public String getName() { return "fichier"; }
    @Override public String getOriginalFilename() { return nom; }
    @Override public String getContentType() { return typeMime; }
    @Override public boolean isEmpty() { return contenu.length == 0; }
    @Override public long getSize() { return contenu.length; }
    @Override public byte[] getBytes() { return contenu.clone(); }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(contenu); }

    @Override
    public void transferTo(File destination) throws IOException {
        Files.write(destination.toPath(), contenu);
    }
}

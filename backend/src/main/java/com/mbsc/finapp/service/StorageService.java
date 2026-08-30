package com.mbsc.finapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stockage des fichiers uploades (pieces jointes) sur le disque local, sous
 * le repertoire {@code app.storage.location}. Chaque fichier est renomme
 * avec un UUID pour eviter toute collision ou traversee de repertoire, le
 * nom d'origine etant conserve separement (en base) pour l'affichage/le
 * telechargement.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final Path racine;

    public StorageService(@Value("${app.storage.location}") String location) {
        this.racine = Path.of(location).toAbsolutePath().normalize();
        try {
            Files.createDirectories(racine);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de creer le repertoire de stockage " + racine, e);
        }
    }

    /**
     * Enregistre le fichier sous {@code sousDossier} et retourne le chemin
     * relatif (a stocker en base) permettant de le retrouver ensuite.
     */
    public String enregistrer(String sousDossier, MultipartFile fichier) {
        try {
            Path dossier = racine.resolve(sousDossier).normalize();
            if (!dossier.startsWith(racine)) {
                throw new IllegalArgumentException("Chemin de stockage invalide");
            }
            Files.createDirectories(dossier);

            String extension = extensionDe(fichier.getOriginalFilename());
            String nomStocke = UUID.randomUUID() + extension;
            Path cible = dossier.resolve(nomStocke);
            fichier.transferTo(cible);

            String cheminRelatif = racine.relativize(cible).toString().replace('\\', '/');
            log.info("Fichier stocke [chemin={}, taille={}]", cheminRelatif, fichier.getSize());
            return cheminRelatif;
        } catch (IOException e) {
            throw new UncheckedIOException("Echec de l'enregistrement du fichier", e);
        }
    }

    /** Charge un fichier precedemment stocke a partir de son chemin relatif. */
    public Resource charger(String cheminRelatif) {
        Path fichier = racine.resolve(cheminRelatif).normalize();
        if (!fichier.startsWith(racine)) {
            throw new IllegalArgumentException("Chemin de stockage invalide");
        }
        if (!Files.exists(fichier)) {
            throw new IllegalStateException("Fichier introuvable sur le disque : " + cheminRelatif);
        }
        return new FileSystemResource(fichier);
    }

    /** Supprime un fichier precedemment stocke (best-effort). */
    public void supprimer(String cheminRelatif) {
        try {
            Path fichier = racine.resolve(cheminRelatif).normalize();
            if (fichier.startsWith(racine)) {
                Files.deleteIfExists(fichier);
            }
        } catch (IOException e) {
            log.warn("Impossible de supprimer le fichier {} : {}", cheminRelatif, e.getMessage());
        }
    }

    private String extensionDe(String nomOriginal) {
        if (!StringUtils.hasText(nomOriginal)) return "";
        int i = nomOriginal.lastIndexOf('.');
        return i >= 0 ? nomOriginal.substring(i) : "";
    }
}

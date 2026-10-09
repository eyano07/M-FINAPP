package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.enums.ModeleEntete;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Thème des documents (modèle de papier à en-tête, couleur, identité et logo de l'entreprise), lu une fois
 * par document dans les paramètres de l'entreprise. Point d'entrée unique pour que tous les PDF suivent le
 * thème choisi par l'administrateur — voir {@link EnteteDocumentRendu}.
 */
@Service
@RequiredArgsConstructor
public class ThemeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ThemeDocumentService.class);
    private static final String LOGO_PAR_DEFAUT = "/pdf/papier_entete_logo.png";

    private final ParametresEntrepriseService parametresService;
    private final StorageService storage;

    public ThemeDocument theme() {
        return theme(null, null);
    }

    /**
     * Thème courant, avec possibilité d'imposer un modèle ou une couleur (aperçu depuis l'écran Paramètres,
     * avant enregistrement). Une couleur invalide est ignorée.
     */
    public ThemeDocument theme(ModeleEntete modeleImpose, String couleurImposee) {
        ParametresEntreprise entreprise = parametresService.obtenirEntite();
        ModeleEntete modele = modeleImpose != null ? modeleImpose
            : entreprise.getModeleEntete() != null ? entreprise.getModeleEntete() : ModeleEntete.CLASSIQUE;
        float[] couleur = ThemeDocument.rgb(couleurImposee);
        if (couleur == null) couleur = ThemeDocument.rgb(entreprise.getCouleurPrimaire());
        if (couleur == null) couleur = ThemeDocument.rgb("#15803D");
        return new ThemeDocument(modele, couleur, entreprise, logo(entreprise));
    }

    /**
     * Logo téléversé (PNG ou JPEG, seuls formats lisibles par PDFBox), sinon le logo fourni avec
     * l'application. Lu directement dans le stockage : {@code telechargerLogo()} lève une exception sans logo,
     * ce qui marquerait la transaction appelante « rollback-only ».
     */
    private byte[] logo(ParametresEntreprise entreprise) {
        String type = entreprise.getLogoTypeMime() == null ? "" : entreprise.getLogoTypeMime();
        if (entreprise.getLogoCheminStockage() != null && (type.contains("png") || type.contains("jpeg") || type.contains("jpg"))) {
            try (InputStream in = storage.charger(entreprise.getLogoCheminStockage()).getInputStream()) {
                return in.readAllBytes();
            } catch (Exception e) {
                log.warn("Logo de l'entreprise illisible, logo par défaut utilisé : {}", e.getMessage());
            }
        }
        try (InputStream in = getClass().getResourceAsStream(LOGO_PAR_DEFAUT)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** Thème d'un document : modèle, couleur (RGB 0-1), entreprise et logo (octets, éventuellement absent). */
    public record ThemeDocument(ModeleEntete modele, float[] couleur, ParametresEntreprise entreprise, byte[] logo) {

        /** Variante foncée de la couleur (titres, textes sur fond clair). */
        public float[] fonce() {
            return melange(new float[] {0, 0, 0}, 0.32f);
        }

        /** Teinte très claire (fonds de bandeaux et de cartouches). */
        public float[] clair() {
            return melange(new float[] {1, 1, 1}, 0.9f);
        }

        /** Mélange avec {@code autre} : 0 = couleur du thème, 1 = {@code autre}. */
        public float[] melange(float[] autre, float part) {
            return new float[] {
                couleur[0] + (autre[0] - couleur[0]) * part,
                couleur[1] + (autre[1] - couleur[1]) * part,
                couleur[2] + (autre[2] - couleur[2]) * part,
            };
        }

        static float[] rgb(String hex) {
            if (hex == null || !hex.matches("^#?[0-9A-Fa-f]{6}$")) return null;
            String h = hex.replace("#", "");
            return new float[] {
                Integer.parseInt(h.substring(0, 2), 16) / 255f,
                Integer.parseInt(h.substring(2, 4), 16) / 255f,
                Integer.parseInt(h.substring(4, 6), 16) / 255f,
            };
        }
    }
}

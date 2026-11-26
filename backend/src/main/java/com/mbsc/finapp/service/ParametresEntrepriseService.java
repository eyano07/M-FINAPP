package com.mbsc.finapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.dto.parametrage.InformationsLiasse;
import com.mbsc.finapp.dto.parametrage.ParametresEntrepriseRequest;
import com.mbsc.finapp.dto.parametrage.ParametresEntrepriseResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * Identite visuelle de l'entreprise (nom, nom complet, slogan, logo) :
 * ligne unique en base, creee paresseusement au premier acces.
 */
@Service
@RequiredArgsConstructor
public class ParametresEntrepriseService {

    private static final Set<String> MIME_LOGO_AUTORISES = Set.of(
        // Pas de SVG : un SVG peut porter du script, exécuté sur le domaine de
        // l'application quand le logo est ouvert (audit sécurité du 05/10/2026, S-08).
        "image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final long TAILLE_MAX_OCTETS = 5L * 1024 * 1024;

    private final ParametresEntrepriseRepository repository;
    private final StorageService storage;
    private final ObjectMapper objectMapper;

    // Pas readOnly : premier appel sur une base sans ligne encore creee,
    // charger() doit pouvoir executer l'INSERT paresseux ci-dessous (une
    // transaction en lecture seule le refuse : "cannot execute INSERT in a
    // read-only transaction").
    @Transactional
    public ParametresEntrepriseResponse obtenir() {
        return ParametresEntrepriseResponse.from(charger());
    }

    /** Entité brute, pour les autres services qui ont besoin de champs non exposés par {@link ParametresEntrepriseResponse}
     * (ex. {@code OrdreMissionPdfService}, qui recompose le papier à en-tête à partir des valeurs courantes). */
    @Transactional
    public ParametresEntreprise obtenirEntite() {
        return charger();
    }

    private ParametresEntreprise charger() {
        return repository.findAll().stream().findFirst()
            .orElseGet(() -> repository.save(ParametresEntreprise.builder().nom("MBSC Finapp").build()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ParametresEntrepriseResponse mettreAJour(ParametresEntrepriseRequest req) {
        ParametresEntreprise p = charger();
        p.setNom(req.nom());
        p.setNomComplet(req.nomComplet());
        p.setSlogan(req.slogan());
        p.setAdresse(req.adresse());
        p.setTelephone(req.telephone());
        p.setEmail(req.email());
        p.setRccm(req.rccm());
        p.setIdNat(req.idNat());
        p.setNif(req.nif());
        p.setCouleurPrimaire(req.couleurPrimaire());
        p.setAdresseServeur(normaliserAdresseServeur(req.adresseServeur()));
        if (req.modeleEntete() != null) {
            p.setModeleEntete(req.modeleEntete());
        }
        p = repository.save(p);
        return ParametresEntrepriseResponse.from(p);
    }

    /**
     * Adresse saisie → adresse lue par les applications : http:// ajouté s'il manque, sans « / » final ni « /api »
     * (même règle que l'écran de configuration des applications) ; vide → null.
     */
    static String normaliserAdresseServeur(String saisie) {
        if (saisie == null || saisie.isBlank()) return null;
        String s = saisie.trim();
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.toLowerCase(java.util.Locale.ROOT).endsWith("/api")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Renseignements de la liasse OHADA (page de garde, fiches R1 à R3) — ADMIN. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public InformationsLiasse obtenirLiasse() {
        return lireLiasse(charger());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public InformationsLiasse mettreAJourLiasse(InformationsLiasse infos) {
        ParametresEntreprise p = charger();
        try {
            p.setInformationsLiasse(infos == null ? null : objectMapper.writeValueAsString(infos));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Renseignements de la liasse illisibles", e);
        }
        return lireLiasse(repository.save(p));
    }

    /**
     * Renseignements de la liasse de l'entité, jamais nuls : une valeur absente ou
     * illisible (JSON d'une version antérieure abîmé) donne une liasse vide plutôt
     * qu'un export d'états financiers en échec.
     */
    public InformationsLiasse lireLiasse(ParametresEntreprise p) {
        String json = p == null ? null : p.getInformationsLiasse();
        if (json == null || json.isBlank()) {
            return InformationsLiasse.vide();
        }
        try {
            return objectMapper.readValue(json, InformationsLiasse.class);
        } catch (JsonProcessingException e) {
            return InformationsLiasse.vide();
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ParametresEntrepriseResponse mettreAJourLogo(MultipartFile fichier) {
        if (fichier == null || fichier.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide ou absent");
        }
        if (fichier.getSize() > TAILLE_MAX_OCTETS) {
            throw new IllegalArgumentException("Le logo depasse la taille maximale autorisee (5 Mo)");
        }
        String type = fichier.getContentType();
        if (type == null || !MIME_LOGO_AUTORISES.contains(type.toLowerCase())) {
            throw new IllegalArgumentException("Format non autorise. Formats acceptes : JPEG, PNG, WEBP");
        }

        ParametresEntreprise p = charger();
        String ancienChemin = p.getLogoCheminStockage();
        p.setLogoCheminStockage(storage.enregistrer("parametres", fichier));
        p.setLogoTypeMime(type);
        p = repository.save(p);
        if (ancienChemin != null) {
            storage.supprimer(ancienChemin);
        }
        return ParametresEntrepriseResponse.from(p);
    }

    // Meme raison que obtenir() : charger() peut inserer la ligne par defaut.
    @Transactional
    public LogoTelechargement telechargerLogo() {
        ParametresEntreprise p = charger();
        if (p.getLogoCheminStockage() == null) {
            throw new RessourceIntrouvableException("Aucun logo n'est configure");
        }
        return new LogoTelechargement(storage.charger(p.getLogoCheminStockage()), p.getLogoTypeMime());
    }

    public record LogoTelechargement(Resource ressource, String typeMime) {}
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
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
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/svg+xml");
    private static final long TAILLE_MAX_OCTETS = 5L * 1024 * 1024;

    private final ParametresEntrepriseRepository repository;
    private final StorageService storage;

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
        p = repository.save(p);
        return ParametresEntrepriseResponse.from(p);
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
            throw new IllegalArgumentException("Format non autorise. Formats acceptes : JPEG, PNG, WEBP, SVG");
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

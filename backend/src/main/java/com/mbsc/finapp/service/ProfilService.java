package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.PreferenceUtilisateur;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.profil.ChangerMotDePasseRequest;
import com.mbsc.finapp.dto.profil.ProfilResponse;
import com.mbsc.finapp.dto.profil.ProfilUpdateRequest;
import com.mbsc.finapp.repository.PreferenceUtilisateurRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Auto-service du profil : chaque utilisateur consulte/modifie ses propres
 * informations (nom, prenom, telephone, photo, mot de passe). A la
 * difference de AdminService (gestion des AUTRES utilisateurs, reservee a
 * ADMIN), ici la cible est toujours l'appelant lui-meme -
 * {@link CurrentUserProvider}, jamais un {id} de chemin.
 */
@Service
@RequiredArgsConstructor
public class ProfilService {

    private static final Set<String> MIME_PHOTO_AUTORISES = Set.of(
        "image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final long TAILLE_MAX_OCTETS = 5L * 1024 * 1024;

    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final StorageService storage;
    private final PasswordEncoder passwordEncoder;
    private final PreferenceUtilisateurRepository preferences;

    /**
     * Preferences d'affichage que l'utilisateur peut enregistrer sur son compte : cle -> valeurs admises. Toute autre cle ou
     * valeur est refusee (400) et rien n'est stocke : la table ne sert pas de fourre-tout.
     */
    static final Map<String, Set<String>> PREFERENCES_AUTORISEES = Map.of(
        // Page « Notes de frais » sur PC : cartes ou tableau.
        "notes.affichage", Set.of("CARTES", "TABLEAU"));

    /** Preferences de l'appelant (jamais celles d'un autre utilisateur) : cle -> valeur. */
    @Transactional(readOnly = true)
    public Map<String, String> preferences() {
        User u = currentUser.requireUser();
        Map<String, String> resultat = new TreeMap<>();
        for (PreferenceUtilisateur p : preferences.findByUserId(u.getId())) {
            resultat.put(p.getCle(), p.getValeur());
        }
        return resultat;
    }

    /** Cree ou met a jour une preference de l'appelant (une seule ligne par cle) et renvoie toutes ses preferences. */
    @Transactional
    public Map<String, String> definirPreference(String cle, String valeur) {
        Set<String> admises = PREFERENCES_AUTORISEES.get(cle);
        if (admises == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Préférence inconnue.");
        }
        if (!admises.contains(valeur)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valeur invalide pour cette préférence.");
        }
        User u = currentUser.requireUser();
        PreferenceUtilisateur p = preferences.findByUserIdAndCle(u.getId(), cle)
            .orElseGet(() -> PreferenceUtilisateur.builder().user(u).cle(cle).build());
        p.setValeur(valeur);
        preferences.save(p);
        return preferences();
    }

    @Transactional(readOnly = true)
    public ProfilResponse obtenir() {
        return ProfilResponse.from(currentUser.requireUser());
    }

    @Transactional
    public ProfilResponse mettreAJour(ProfilUpdateRequest req) {
        User u = currentUser.requireUser();
        u.setNom(req.nom().trim());
        u.setPrenom(req.prenom().trim());
        u.setTelephone(videEnNull(req.telephone()));
        u.setEmailNotification(videEnNull(req.emailNotification() == null ? null : req.emailNotification().trim().toLowerCase()));
        return ProfilResponse.from(userRepository.save(u));
    }

    @Transactional
    public void changerMotDePasse(ChangerMotDePasseRequest req) {
        User u = currentUser.requireUser();
        if (!passwordEncoder.matches(req.ancienMotDePasse(), u.getMotDePasse())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe actuel incorrect.");
        }
        u.setMotDePasse(passwordEncoder.encode(req.nouveauMotDePasse()));
        // Toutes les sessions sont fermées, celle-ci comprise : l'écran
        // renvoie à la connexion avec le nouveau mot de passe.
        u.revoquerJetons();
        userRepository.save(u);
    }

    @Transactional
    public ProfilResponse mettreAJourPhoto(MultipartFile fichier) {
        if (fichier == null || fichier.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide ou absent");
        }
        if (fichier.getSize() > TAILLE_MAX_OCTETS) {
            throw new IllegalArgumentException("La photo depasse la taille maximale autorisee (5 Mo)");
        }
        String type = fichier.getContentType();
        if (type == null || !MIME_PHOTO_AUTORISES.contains(type.toLowerCase())) {
            throw new IllegalArgumentException("Format non autorise. Formats acceptes : JPEG, PNG, WEBP");
        }

        User u = currentUser.requireUser();
        String ancienChemin = u.getPhotoCheminStockage();
        u.setPhotoCheminStockage(storage.enregistrer("profils", fichier));
        u.setPhotoTypeMime(type);
        ProfilResponse rep = ProfilResponse.from(userRepository.save(u));
        if (ancienChemin != null) {
            storage.supprimer(ancienChemin);
        }
        return rep;
    }

    @Transactional(readOnly = true)
    public PhotoTelechargement telechargerPhoto() {
        User u = currentUser.requireUser();
        if (u.getPhotoCheminStockage() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune photo de profil configuree");
        }
        return new PhotoTelechargement(storage.charger(u.getPhotoCheminStockage()), u.getPhotoTypeMime());
    }

    private String videEnNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    public record PhotoTelechargement(Resource ressource, String typeMime) {}
}

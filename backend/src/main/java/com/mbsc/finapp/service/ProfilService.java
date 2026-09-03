package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.profil.ChangerMotDePasseRequest;
import com.mbsc.finapp.dto.profil.ProfilResponse;
import com.mbsc.finapp.dto.profil.ProfilUpdateRequest;
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

import java.util.Set;

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
        return ProfilResponse.from(userRepository.save(u));
    }

    @Transactional
    public void changerMotDePasse(ChangerMotDePasseRequest req) {
        User u = currentUser.requireUser();
        if (!passwordEncoder.matches(req.ancienMotDePasse(), u.getMotDePasse())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe actuel incorrect.");
        }
        u.setMotDePasse(passwordEncoder.encode(req.nouveauMotDePasse()));
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

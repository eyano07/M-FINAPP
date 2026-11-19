package com.mbsc.hotellerie.commun;

import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.securite.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Photos des états des lieux, des tickets de maintenance et des relevés de compteurs : le fichier vit dans le volume des
 * téléversements ({@code photos/}), la base n'en garde que la référence. Images seulement (JPEG, PNG, WebP).
 */
@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    public static final Set<String> OBJETS = Set.of("ETAT_LIEUX", "TICKET", "RELEVE");

    private final JdbcTemplate jdbc;
    private final Journal journal;

    @Value("${app.uploads-dir}")
    private String uploads;

    public record Photo(Long id, String objet, Long objetId, String legende, String nomOrigine, String creePar, OffsetDateTime creeLe) { }

    public record Fichier(Path chemin, String type) { }

    public List<Photo> photos(String objet, Long objetId) {
        return jdbc.query("select * from photos where objet = ? and objet_id = ? order by cree_le", (rs, i) -> new Photo(rs.getLong("id"),
            rs.getString("objet"), rs.getLong("objet_id"), rs.getString("legende"), rs.getString("nom_origine"), rs.getString("cree_par"),
            rs.getObject("cree_le", OffsetDateTime.class)), objet, objetId);
    }

    @Transactional
    public Photo ajouter(String objet, Long objetId, MultipartFile fichier, String legende, Session session) {
        if (!OBJETS.contains(objet)) throw Refus.invalide("Objet de photo inconnu.");
        if (fichier == null || fichier.isEmpty()) throw Refus.invalide("Photo vide.");
        String type = fichier.getContentType() == null ? "" : fichier.getContentType();
        if (!TYPES.contains(type)) throw Refus.invalide("Photo : JPEG, PNG ou WebP seulement.");
        if (fichier.getSize() > 10L * 1024 * 1024) throw Refus.invalide("Photo trop lourde (10 Mo au plus).");
        String nom = UUID.randomUUID() + switch (type) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        try {
            Path dossier = Path.of(uploads, "photos");
            Files.createDirectories(dossier);
            fichier.transferTo(dossier.resolve(nom));
        } catch (IOException e) {
            throw new IllegalStateException("Photo non enregistrée : " + e.getMessage(), e);
        }
        Long id = jdbc.queryForObject("""
            insert into photos (objet, objet_id, fichier, nom_origine, type_contenu, legende, cree_par) values (?, ?, ?, ?, ?, ?, ?) returning id
            """, Long.class, objet, objetId, nom, fichier.getOriginalFilename(), type,
            legende == null || legende.isBlank() ? null : legende.strip(), session.email());
        journal.noter("PHOTO_AJOUTEE", objet + " " + objetId, "photo " + id);
        return photos(objet, objetId).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    public Fichier fichier(Long id) {
        return jdbc.query("select fichier, type_contenu from photos where id = ?",
            (rs, i) -> new Fichier(Path.of(uploads, "photos", rs.getString(1)), rs.getString(2)), id)
            .stream().findFirst().orElseThrow(() -> Refus.introuvable("Photo"));
    }

    @Transactional
    public void supprimer(Long id, Session session) {
        Fichier f = fichier(id);
        jdbc.update("delete from photos where id = ?", id);
        try {
            Files.deleteIfExists(f.chemin());
        } catch (IOException e) {
            // Fichier resté sur le disque : sans conséquence, la référence est retirée.
        }
        journal.noter("PHOTO_RETIREE", "photo " + id, session.email());
    }
}

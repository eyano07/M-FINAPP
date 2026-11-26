package com.mbsc.hotellerie.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.audit.Journal;
import com.mbsc.hotellerie.commun.Refus;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.securite.Session;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fiche client (CRM) : identité, pièce, préférences, notes, historique des séjours. Le lien vers le client M-FINAPP
 * (compte 411x) se crée à la demande, pour les séjours facturés à crédit ou un client régulier.
 */
@Service
@RequiredArgsConstructor
public class ClientService {

    private static final Set<String> TYPES_PIECE = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final FinappClient finapp;
    @Value("${app.uploads-dir}")
    private String uploads;

    public record Client(Long id, String nom, String prenom, String telephone, String email, String nationalite,
                         LocalDate dateNaissance, String pieceType, String pieceNumero, String adresse, String entreprise,
                         String preferences, String notes, Long finappClientId, int sejours) {
        public String nomComplet() {
            return (prenom == null || prenom.isBlank() ? "" : prenom + " ") + nom;
        }
    }

    public record ClientRequest(@NotBlank @Size(max = 120) String nom, @Size(max = 120) String prenom,
                                @Size(max = 40) String telephone, @Email @Size(max = 150) String email,
                                @Size(max = 80) String nationalite, LocalDate dateNaissance, @Size(max = 40) String pieceType,
                                @Size(max = 60) String pieceNumero, @Size(max = 255) String adresse,
                                @Size(max = 150) String entreprise, String preferences, String notes) { }

    public record Sejour(Long id, String reference, OffsetDateTime debut, OffsetDateTime fin, String unite, String statut,
                         String venteReference) { }

    public record Piece(Long id, String nomOrigine, String typeContenu, OffsetDateTime creeLe, String creePar) { }

    public record Fiche(Client client, List<Sejour> sejours, List<Piece> pieces) { }

    private static final RowMapper<Client> LIGNE = (rs, i) -> new Client(rs.getLong("id"), rs.getString("nom"),
        rs.getString("prenom"), rs.getString("telephone"), rs.getString("email"), rs.getString("nationalite"),
        rs.getDate("date_naissance") == null ? null : rs.getDate("date_naissance").toLocalDate(), rs.getString("piece_type"),
        rs.getString("piece_numero"), rs.getString("adresse"), rs.getString("entreprise"), rs.getString("preferences"),
        rs.getString("notes"), (Long) rs.getObject("finapp_client_id"), rs.getInt("nb"));

    public List<Client> rechercher(String q) {
        String motif = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase() + "%";
        return jdbc.query("""
            select c.*, (select count(*) from reservations r where r.client_id = c.id and r.statut in ('ARRIVEE', 'PARTIE')) as nb
            from clients c
            where ?::text is null or lower(c.nom || ' ' || coalesce(c.prenom, '') || ' ' || coalesce(c.telephone, '') || ' '
                  || coalesce(c.email, '') || ' ' || coalesce(c.piece_numero, '') || ' ' || coalesce(c.entreprise, '')) like ?
            order by c.nom, c.prenom limit 100
            """, LIGNE, motif, motif);
    }

    public Client client(Long id) {
        return jdbc.query("""
            select c.*, (select count(*) from reservations r where r.client_id = c.id and r.statut in ('ARRIVEE', 'PARTIE')) as nb
            from clients c where c.id = ?
            """, LIGNE, id).stream().findFirst().orElseThrow(() -> Refus.introuvable("Client"));
    }

    public Fiche fiche(Long id) {
        Client c = client(id);
        List<Sejour> sejours = jdbc.query("""
            select r.id, r.reference, r.debut, r.fin, u.numero, r.statut, r.vente_reference from reservations r
            join unites u on u.id = r.unite_id where r.client_id = ? order by r.debut desc
            """, (rs, i) -> new Sejour(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
            rs.getObject(4, OffsetDateTime.class), rs.getString(5), rs.getString(6), rs.getString(7)), id);
        List<Piece> pieces = jdbc.query("""
            select id, nom_origine, type_contenu, cree_le, cree_par from pieces_identite where client_id = ? order by cree_le desc
            """, (rs, i) -> new Piece(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
            rs.getString(5)), id);
        return new Fiche(c, sejours, pieces);
    }

    @Transactional
    public Client enregistrer(Long id, ClientRequest r) {
        Object[] valeurs = {r.nom().strip(), vide(r.prenom()), vide(r.telephone()), vide(r.email()), vide(r.nationalite()),
            r.dateNaissance() == null ? null : Date.valueOf(r.dateNaissance()), vide(r.pieceType()), vide(r.pieceNumero()),
            vide(r.adresse()), vide(r.entreprise()), vide(r.preferences()), vide(r.notes())};
        if (id == null) {
            id = jdbc.queryForObject("""
                insert into clients (nom, prenom, telephone, email, nationalite, date_naissance, piece_type, piece_numero,
                adresse, entreprise, preferences, notes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, valeurs);
            journal.noter("CLIENT_CREE", "client " + id, r.nom());
        } else {
            Object[] avecId = java.util.Arrays.copyOf(valeurs, valeurs.length + 1);
            avecId[valeurs.length] = id;
            if (jdbc.update("""
                update clients set nom = ?, prenom = ?, telephone = ?, email = ?, nationalite = ?, date_naissance = ?, piece_type = ?,
                piece_numero = ?, adresse = ?, entreprise = ?, preferences = ?, notes = ? where id = ?
                """, avecId) == 0) {
                throw Refus.introuvable("Client");
            }
            journal.noter("CLIENT_MODIFIE", "client " + id, r.nom());
        }
        return client(id);
    }

    /** Crée (une fois) le client correspondant dans M-FINAPP, avec son compte 411x : séjours à crédit, client régulier. */
    @Transactional
    public Client lierFinapp(Long id, Session session) {
        Client c = client(id);
        if (c.finappClientId() != null) return c;
        Map<String, Object> corps = new HashMap<>();
        corps.put("nom", c.entreprise() != null ? c.entreprise() : c.nomComplet());
        corps.put("telephone", c.telephone());
        corps.put("email", c.email());
        corps.put("adresse", c.adresse());
        corps.put("typeClient", c.entreprise() != null ? "ENTREPRISE" : "PARTICULIER");
        JsonNode cree = finapp.signe("POST", "/pms/clients", corps, session.jeton());
        long finappId = cree.path("id").asLong();
        jdbc.update("update clients set finapp_client_id = ? where id = ?", finappId, id);
        journal.noter("CLIENT_LIE_FINAPP", "client " + id, "client M-FINAPP " + cree.path("code").asText());
        return client(id);
    }

    @Transactional
    public Piece ajouterPiece(Long clientId, MultipartFile fichier, Session session) {
        client(clientId);
        if (fichier == null || fichier.isEmpty()) throw Refus.invalide("Fichier vide.");
        String type = fichier.getContentType() == null ? "" : fichier.getContentType();
        if (!TYPES_PIECE.contains(type)) throw Refus.invalide("Pièce d'identité : photo (JPEG, PNG, WebP) ou PDF seulement.");
        String nom = UUID.randomUUID() + switch (type) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> ".jpg";
        };
        try {
            Path dossier = Path.of(uploads, "pieces");
            Files.createDirectories(dossier);
            fichier.transferTo(dossier.resolve(nom));
        } catch (IOException e) {
            throw new IllegalStateException("Pièce non enregistrée : " + e.getMessage(), e);
        }
        Long id = jdbc.queryForObject("""
            insert into pieces_identite (client_id, fichier, nom_origine, type_contenu, cree_par) values (?, ?, ?, ?, ?) returning id
            """, Long.class, clientId, nom, fichier.getOriginalFilename(), type, session.email());
        journal.noter("PIECE_IDENTITE", "client " + clientId, "pièce " + id + " ajoutée");
        return fiche(clientId).pieces().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    public record Fichier(Path chemin, String type, String nom) { }

    public Fichier piece(Long clientId, Long pieceId) {
        return jdbc.query("select fichier, type_contenu, nom_origine from pieces_identite where id = ? and client_id = ?",
            (rs, i) -> new Fichier(Path.of(uploads, "pieces", rs.getString(1)), rs.getString(2), rs.getString(3)), pieceId, clientId)
            .stream().findFirst().orElseThrow(() -> Refus.introuvable("Pièce"));
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archive figée des livres et des états à chaque clôture d'exercice (audit SYSCOHADA du 10/11/2026, C-13 ; AUDCIF
 * art. 24 : conservation dix ans).
 *
 * <p>À la clôture, un fichier ZIP réunit le classeur des états financiers de l'exercice (journal, balance, bilan,
 * compte de résultat, tableau des flux, notes) et le fichier de toutes les écritures. Il est enregistré en base avec
 * son empreinte SHA-256, dans une table en ajout seul : l'archive voyage avec chaque sauvegarde de la base et ne peut
 * être ni modifiée ni supprimée. Retéléchargée plus tard, elle se compare à son empreinte.</p>
 */
@Service
@RequiredArgsConstructor
public class ArchiveClotureService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveClotureService.class);

    private final JdbcTemplate jdbc;
    private final EtatsFinanciersExcelService etats;
    private final ExportEcrituresService export;

    /** Une archive, sans son contenu. */
    public record Archive(Long id, LocalDate dateCloture, String nomFichier, long taille, String empreinte,
                          String creePar, java.time.LocalDateTime creeLe) {
    }

    @Transactional
    public void archiver(LocalDate dateCloture, User auteur) {
        LocalDate debut = dateCloture.withDayOfYear(1);
        byte[] zip = zip(Map.of(
            "etats_financiers_" + dateCloture.getYear() + ".xlsx", etats.genererClasseurArchive(debut, dateCloture),
            "ecritures_" + dateCloture.getYear() + ".csv", export.fichierEcrituresInterne(debut, dateCloture)));
        String nom = "cloture_" + dateCloture + ".zip";
        String empreinte = sha256(zip);
        jdbc.update("""
            insert into archives_cloture (date_cloture, nom_fichier, contenu, taille, empreinte, cree_par_id)
            values (?, ?, ?, ?, ?, ?)
            """, dateCloture, nom, zip, zip.length, empreinte, auteur.getId());
        log.info("Archive de clôture {} enregistrée ({} octets, SHA-256 {})", nom, zip.length, empreinte);
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<Archive> lister() {
        return jdbc.query("""
            select a.id, a.date_cloture, a.nom_fichier, a.taille, a.empreinte, u.email, a.cree_le
            from archives_cloture a left join users u on u.id = a.cree_par_id
            order by a.date_cloture desc, a.id desc
            """, (rs, i) -> new Archive(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3),
            rs.getLong(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime()));
    }

    /** Contenu d'une archive ; son empreinte est revérifiée avant de la rendre. */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public byte[] contenu(Long id) {
        Map<String, Object> ligne = jdbc.queryForMap("select contenu, empreinte from archives_cloture where id = ?", id);
        byte[] contenu = (byte[]) ligne.get("contenu");
        if (!sha256(contenu).equals(((String) ligne.get("empreinte")).trim())) {
            throw new IllegalStateException("L'archive " + id + " ne correspond plus à son empreinte : elle a été altérée.");
        }
        return contenu;
    }

    private static byte[] zip(Map<String, byte[]> fichiers) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            for (var f : new java.util.TreeMap<>(fichiers).entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue());
                z.closeEntry();
            }
            z.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Archive de clôture impossible", e);
        }
    }

    static String sha256(byte[] contenu) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.mbsc.finapp.audit;

import com.mbsc.finapp.audit.AuditModele.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AuditLecteurServiceTest {

    private static String ligne(String ts, String email, String module, String op, int statut) {
        return "{\"horodatage\":\"" + ts + "\",\"type\":\"ACTION\",\"utilisateurId\":1,\"email\":\"" + email + "\",\"roles\":[\"CAISSIER\"],"
            + "\"ip\":\"1.2.3.4\",\"module\":\"" + module + "\",\"operation\":\"" + op + "\",\"ressourceId\":\"5\",\"methode\":\"POST\","
            + "\"chemin\":\"/" + module + "\",\"statut\":" + statut + ",\"reussi\":" + (statut < 400) + ",\"dureeMs\":10}";
    }

    @Test
    void litLesArchivesEtLeFichierCourantAvecFiltres(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("audit.log"), String.join("\n",
            ligne("2026-10-10T08:00:00Z", "a@x.cd", "ventes", "VALIDER", 200),
            ligne("2026-10-10T09:00:00Z", "b@x.cd", "admin/ia", "MODIFIER", 403),
            "ligne corrompue", "") , StandardCharsets.UTF_8);
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(dir.resolve("audit-2026-10-09.log.gz")))) {
            o.write(ligne("2026-10-09T10:00:00Z", "a@x.cd", "clients", "CREER", 201).getBytes(StandardCharsets.UTF_8));
        }
        AuditLecteurService s = new AuditLecteurService(dir.toString());
        LocalDate d9 = LocalDate.of(2026, 10, 9);
        LocalDate d10 = LocalDate.of(2026, 10, 10);

        var tout = s.lire(new Filtre(d9, d10, null, null, null, null, null, null));
        assertEquals(3, tout.evenements().size());
        assertEquals("b@x.cd", tout.evenements().get(0).email());      // du plus récent au plus ancien

        assertEquals(1, s.lire(new Filtre(d9, d10, "b@x", null, null, null, null, null)).evenements().size());
        assertEquals(1, s.lire(new Filtre(d9, d10, null, null, null, "ECHEC", null, null)).evenements().size());
        assertEquals(2, s.lire(new Filtre(d9, d10, null, null, null, "OK", null, null)).evenements().size());
        assertEquals(1, s.lire(new Filtre(d9, d10, null, "clients", null, null, null, null)).evenements().size());
        assertEquals(1, s.lire(new Filtre(d9, d10, null, null, "VALIDER", null, null, null)).evenements().size());
        assertEquals(2, s.lire(new Filtre(d10, d10, null, null, null, null, null, null)).evenements().size());   // archive du 9 exclue

        Synthese syn = s.synthese(new Filtre(d9, d10, null, null, null, null, null, null));
        assertEquals(3, syn.total());
        assertEquals(1, syn.echecs());
        assertEquals(2, syn.utilisateursActifs());
        assertEquals("a@x.cd", syn.parUtilisateur().get(0).cle());
        assertEquals(2, syn.parJour().size());

        Page p = s.page(new Filtre(d9, d10, null, null, null, null, null, null), 1, 2);
        assertEquals(3, p.total());
        assertEquals(1, p.evenements().size());
    }

    @Test
    void dossierAbsentEtExportCsvProtege(@TempDir Path dir) throws Exception {
        assertTrue(new AuditLecteurService(dir.resolve("nope").toString()).lire(new Filtre(null, null, null, null, null, null, null, null)).evenements().isEmpty());
        assertEquals("\"'=SOMME(A1)\"", AuditLecteurService.cell("=SOMME(A1)"));
        assertEquals("\"a\"\"b\"", AuditLecteurService.cell("a\"b"));
        Files.writeString(dir.resolve("audit.log"), ligne("2026-10-10T08:00:00Z", "a@x.cd", "ventes", "VALIDER", 200) + "\n");
        String csv = new AuditLecteurService(dir.toString()).exporterCsv(new Filtre(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10), null, null, null, null, null, null));
        assertTrue(csv.contains("a@x.cd") && csv.contains("VALIDER") && csv.contains("OK"));
    }
}

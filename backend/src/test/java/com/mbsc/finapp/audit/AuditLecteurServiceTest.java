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

    private static String ligneAvecAgent(String ts, String email, String module, String op, String agent) {
        String base = ligne(ts, email, module, op, 200);
        return base.substring(0, base.length() - 1) + ",\"agent\":\"" + agent + "\"}";
    }

    @Test
    void terminalEtSystemeSontDeduitsDeLEnteteDuNavigateur(@TempDir Path dir) throws Exception {
        String pc = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
        String tel = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
        Files.writeString(dir.resolve("audit.log"), String.join("\n",
            ligneAvecAgent("2026-10-10T08:00:00Z", "a@x.cd", "ventes", "VALIDER", pc),
            ligneAvecAgent("2026-10-10T09:00:00Z", "b@x.cd", "clients", "CREER", tel),
            ligne("2026-10-10T10:00:00Z", "c@x.cd", "ventes", "CREER", 200),      // ligne sans en-tête (avant son enregistrement)
            ""), StandardCharsets.UTF_8);
        AuditLecteurService s = new AuditLecteurService(dir.toString());
        LocalDate d10 = LocalDate.of(2026, 10, 10);

        var tout = s.lire(new Filtre(d10, d10, null, null, null, null, null, null)).evenements();
        assertEquals(3, tout.size());
        assertEquals("Inconnu", tout.get(0).terminal());          // c@x.cd : pas d'en-tête
        assertEquals("Inconnu", tout.get(0).systeme());
        assertNull(tout.get(0).agent());
        assertEquals("Mobile", tout.get(1).terminal());           // b@x.cd
        assertEquals("Android", tout.get(1).systeme());
        assertEquals(tel, tout.get(1).agent());
        assertEquals("Ordinateur", tout.get(2).terminal());       // a@x.cd
        assertEquals("Windows", tout.get(2).systeme());
        assertEquals("Création d'une vente", tout.get(2).libelle());      // POST /ventes : déduit à la lecture
        assertEquals("Création d'un client", tout.get(1).libelle());      // POST /clients

        assertEquals(1, s.lire(new Filtre(d10, d10, null, null, null, null, null, null, "Mobile", null)).evenements().size());
        assertEquals(1, s.lire(new Filtre(d10, d10, null, null, null, null, null, null, null, "windows")).evenements().size());   // casse indifférente
        assertEquals(0, s.lire(new Filtre(d10, d10, null, null, null, null, null, null, "Tablette", null)).evenements().size());
        assertEquals(0, s.lire(new Filtre(d10, d10, null, null, null, null, null, null, "Mobile", "Windows")).evenements().size());
        assertEquals(1, s.lire(new Filtre(d10, d10, null, null, null, null, null, "android", null, null)).evenements().size());   // recherche libre

        Synthese syn = s.synthese(new Filtre(d10, d10, null, null, null, null, null, null));
        assertEquals(java.util.Set.of("Ordinateur", "Mobile", "Inconnu"),
            syn.parTerminal().stream().map(Compte::cle).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("Windows", "Android", "Inconnu"),
            syn.parSysteme().stream().map(Compte::cle).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, syn.parTerminal().stream().filter(c -> c.cle().equals("Mobile")).findFirst().orElseThrow().total());

        String csv = s.exporterCsv(new Filtre(d10, d10, null, null, null, null, null, null));
        assertTrue(csv.contains("IP;Terminal;Systeme;Action;Module"));
        assertTrue(csv.contains("\"Mobile\";\"Android\""));
        assertTrue(csv.contains("\"Ordinateur\";\"Windows\""));
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

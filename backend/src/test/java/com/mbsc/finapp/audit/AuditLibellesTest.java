package com.mbsc.finapp.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AuditLibellesTest {

    @Test
    void importDeJournalDitCeQuilFaitDesEcritures() {
        assertEquals("Analyse d'un fichier de journal comptable (simulation : rien n'est écrit)",
            AuditLibelles.decrire("POST", "/admin/import/journal", "simulation=true&regroupement=JOUR&devise=USD"));
        assertEquals("Analyse d'un fichier de journal comptable (simulation : rien n'est écrit)",
            AuditLibelles.decrire("POST", "/admin/import/journal", null));      // sans paramètre, le serveur simule
        assertEquals("Import d'un journal comptable — remplace toutes les écritures existantes",
            AuditLibelles.decrire("POST", "/admin/import/journal", "simulation=false&regroupement=JOUR&devise=USD&modeImport=REMPLACER"));
        assertEquals("Import d'un journal comptable — ajoute aux écritures existantes",
            AuditLibelles.decrire("POST", "/admin/import/journal", "simulation=false&modeImport=AJOUTER"));
        // Lignes écrites avant que le mode soit transmis : on ne prétend pas le connaître.
        assertEquals("Import d'un journal comptable (mode Remplacer ou Ajouter non enregistré)",
            AuditLibelles.decrire("POST", "/admin/import/journal", "simulation=false&regroupement=JOUR&devise=USD"));
    }

    @Test
    void lesLibellesReprennentLesElementsVariablesDuChemin() {
        assertEquals("Activation ou désactivation du module « Mobile Money »", AuditLibelles.decrire("PUT", "/admin/modules/MOBILE_MONEY", null));
        assertEquals("Activation ou désactivation du module « DRH › Paie »", AuditLibelles.decrire("PUT", "/admin/modules/DRH_PAIE", null));
        assertEquals("Modification des droits du rôle DA sur le module « Budget »", AuditLibelles.decrire("PUT", "/admin/permissions/DA/BUDGET", null));
        assertEquals("Modification du libellé affiché du rôle DFIN", AuditLibelles.decrire("PUT", "/admin/roles/DFIN/libelle", null));
        assertEquals("Test de la clé de l'IA (OpenAI)", AuditLibelles.decrire("POST", "/admin/ia/openai/tester", null));
        assertEquals("Création de la note de frais de l'organisme IPR (retenues sur salaires)", AuditLibelles.decrire("POST", "/drh/bulletins/reglement/notes-impots/IPR", null));
        assertEquals("Création d'une vente", AuditLibelles.decrire("POST", "/ventes", null));
        assertEquals("Validation d'une note de frais", AuditLibelles.decrire("POST", "/notes-frais/999999/verifier".replace("verifier", "valider"), null));
        assertEquals("Suppression d'une banque ou d'un opérateur mobile money", AuditLibelles.decrire("DELETE", "/etablissements/2", null));
    }

    @Test
    void laRegleLaPlusPreciseEmporte() {
        assertEquals("Modification des seuils de priorité des notes de frais", AuditLibelles.decrire("PUT", "/notes-frais/parametres-priorite", null));
        assertEquals("Modification d'une note de frais", AuditLibelles.decrire("PUT", "/notes-frais/12", null));
        assertEquals("Modification du libellé affiché du rôle DA", AuditLibelles.decrire("PUT", "/admin/roles/DA/libelle", null));
    }

    @Test
    void connexionsEtDeconnexions() {
        assertEquals("Connexion à l'application", AuditLibelles.authentification("CONNEXION", true, null));
        assertEquals("Connexion refusée (identifiants invalides)", AuditLibelles.authentification("CONNEXION", false, "identifiants invalides"));
        assertEquals("Connexion refusée", AuditLibelles.authentification("CONNEXION", false, null));
        assertEquals("Déconnexion", AuditLibelles.authentification("DECONNEXION", true, null));
    }

    @Test
    void uneRouteInconnueRecoitUnLibelleGeneriqueLisible() {
        assertEquals("Action sur nouveau › truc › faire", AuditLibelles.decrire("POST", "/nouveau/truc/12/faire", null));
        assertEquals("Suppression sur nouveau › truc", AuditLibelles.decrire("DELETE", "/nouveau/truc/12", null));
        assertEquals("Modification sur nouveau", AuditLibelles.decrire("PATCH", "/nouveau/", null));
        assertFalse(AuditLibelles.connu("POST", "/nouveau/truc/12/faire"));
        assertTrue(AuditLibelles.connu("POST", "/ventes"));
    }

    /**
     * Garde-fou : toute action d'écriture tracée par le journal doit avoir son libellé dans le catalogue. Le test lit
     * les contrôleurs ; ajouter une route sans l'inscrire dans {@link AuditLibelles} fait échouer le build, au lieu de
     * laisser un « CREER admin/xxx » incompréhensible dans le journal.
     */
    @Test
    void toutesLesActionsDEcritureTraceesOntUnLibelle() throws IOException {
        Path dossier = Path.of("src/main/java/com/mbsc/finapp/controller");
        assertTrue(Files.isDirectory(dossier), "dossier des contrôleurs introuvable : " + dossier.toAbsolutePath());
        Pattern mapping = Pattern.compile("@(Post|Put|Delete|Patch)Mapping(?:\\(([^)]*)\\))?");
        Pattern chemin = Pattern.compile("^\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]*)\"");
        Pattern base = Pattern.compile("@RequestMapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]*)\"");
        List<String> manquantes = new ArrayList<>();
        int examinees = 0;
        try (Stream<Path> fichiers = Files.list(dossier)) {
            for (Path f : fichiers.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                int classe = Math.max(0, src.indexOf("public class "));
                Matcher b = base.matcher(src.substring(0, classe));
                String prefixe = b.find() ? b.group(1) : "";
                Matcher m = mapping.matcher(src.substring(classe));
                while (m.find()) {
                    String verbe = m.group(1).toUpperCase();
                    Matcher p = chemin.matcher(m.group(2) == null ? "" : m.group(2));
                    String brut = prefixe + (p.find() ? p.group(1) : "");
                    String concret = brut.replaceAll("\\{[^}]+}", "1");
                    if (!AuditService.aTracer(verbe, concret)) continue;
                    examinees++;
                    if (!AuditLibelles.connu(verbe, concret)) manquantes.add(verbe + " " + brut + "  (" + f.getFileName() + ")");
                }
            }
        }
        assertTrue(examinees > 150, "le test doit examiner toutes les routes d'écriture, pas " + examinees);
        assertTrue(manquantes.isEmpty(), "Actions d'écriture sans libellé dans AuditLibelles : " + manquantes);
    }
}

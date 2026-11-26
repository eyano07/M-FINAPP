package com.mbsc.finapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.dto.rapprochement.ExtractionReleveResponse;
import com.mbsc.finapp.dto.rapprochement.LigneReleveRequest;
import com.mbsc.finapp.exception.TransitionInvalideException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extraction d'un relevé bancaire ou mobile money par l'IA : le fichier (PDF, photo ou scan, Excel, CSV)
 * est lu par le modèle, qui renvoie les soldes et les opérations au format JSON. Rien n'est enregistré :
 * l'utilisateur relit et corrige le résultat avant de créer le relevé ({@code RapprochementService}).
 *
 * <p>Les PDF et les images sont transmis tels quels au modèle (il lit aussi les relevés scannés) ; les
 * classeurs et CSV sont d'abord convertis en texte tabulé.</p>
 */
@Service
@RequiredArgsConstructor
public class ExtractionReleveIaService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionReleveIaService.class);
    private static final long TAILLE_MAX = 15L * 1024 * 1024;
    private static final int LIGNES_TABLEUR_MAX = 3000;

    private final ChatGptClient ia;
    private final ObjectMapper objectMapper;

    private static final String SYSTEME = """
        Tu es un assistant comptable. Tu extrais fidèlement les données d'un relevé de compte bancaire ou de \
        mobile money, sans rien inventer ni recalculer.
        Règles :
        - Une entrée par opération du relevé, dans l'ordre du relevé. Ignore les lignes de report, de sous-total \
        et de solde.
        - « entree » = argent reçu sur le compte (colonne Crédit d'un relevé bancaire, versement, virement reçu, \
        intérêts créditeurs). « sortie » = argent sorti du compte (colonne Débit, paiement, retrait, chèque, \
        frais, commissions, agios). L'autre montant vaut 0. Montants positifs, sans séparateur de milliers, point \
        décimal.
        - Dates au format AAAA-MM-JJ (date d'opération ; à défaut, date de valeur). Si l'année n'est pas écrite \
        sur la ligne, déduis-la de la période du relevé.
        - « reference » : numéro de chèque, de virement ou de transaction s'il figure, sinon null.
        - « libelle » : le libellé de l'opération tel qu'écrit, sur une ligne.
        - Soldes d'ouverture (ancien solde, solde au début) et de clôture (nouveau solde, solde à la fin) : \
        positifs si créditeurs (en faveur du client), négatifs si débiteurs ; null s'ils ne figurent pas.
        - « devise » : code ISO (USD, CDF...) du compte s'il est indiqué, sinon null.
        """;

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'ADMIN')")
    public ExtractionReleveResponse extraire(MultipartFile fichier, String deviseAttendue) {
        if (fichier == null || fichier.isEmpty()) {
            throw new IllegalArgumentException("Fichier du relevé manquant.");
        }
        if (fichier.getSize() > TAILLE_MAX) {
            throw new IllegalArgumentException("Le fichier dépasse 15 Mo : scindez le relevé ou réduisez sa résolution.");
        }
        if (!ia.disponible()) {
            throw new TransitionInvalideException("L'assistant IA n'est pas configuré : saisissez le relevé manuellement "
                + "(ou demandez à l'administrateur de renseigner la clé de l'IA).");
        }
        String nom = StringUtils.hasText(fichier.getOriginalFilename()) ? fichier.getOriginalFilename() : "releve";
        String type = typeDocument(nom, fichier.getContentType());
        String consigne = "Extrais les soldes et toutes les opérations de ce relevé (fichier « " + nom + " »)."
            + (deviseAttendue != null ? " Le compte est tenu en " + deviseAttendue + "." : "");
        String json;
        try {
            byte[] contenu = fichier.getBytes();
            json = switch (type) {
                case "pdf" -> ia.jsonAvecDocument(SYSTEME, consigne, nom, "application/pdf", contenu, 32000, SCHEMA);
                case "image" -> ia.jsonAvecDocument(SYSTEME, consigne, nom, typeImage(nom, fichier.getContentType()),
                    contenu, 32000, SCHEMA);
                case "tableur" -> ia.json(SYSTEME, consigne + "\n\nContenu du classeur (cellules séparées par des "
                    + "tabulations) :\n" + texteClasseur(contenu), 32000, SCHEMA);
                default -> ia.json(SYSTEME, consigne + "\n\nContenu du fichier :\n"
                    + new String(contenu, StandardCharsets.UTF_8), 32000, SCHEMA);
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("Fichier illisible : " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Extraction IA du relevé {} en échec : {}", nom, e.getMessage());
            throw new TransitionInvalideException("L'IA n'a pas pu lire ce relevé (" + e.getMessage()
                + ") : réessayez ou saisissez-le manuellement.");
        }
        if (json == null) {
            throw new TransitionInvalideException("L'IA n'a renvoyé aucun résultat pour ce relevé : réessayez ou "
                + "saisissez-le manuellement.");
        }
        return interpreter(json, nom, deviseAttendue);
    }

    /** Transforme la réponse JSON du modèle et contrôle sa cohérence (ouverture + mouvements = clôture). */
    ExtractionReleveResponse interpreter(String json, String source, String deviseAttendue) {
        JsonNode racine;
        try {
            racine = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new TransitionInvalideException("Réponse de l'IA illisible : réessayez.");
        }
        List<String> avertissements = new ArrayList<>();
        List<LigneReleveRequest> lignes = new ArrayList<>();
        int ignorees = 0;
        for (JsonNode l : racine.path("lignes")) {
            LocalDate date = date(l.path("date"));
            BigDecimal entree = montant(l.path("entree")).abs();
            BigDecimal sortie = montant(l.path("sortie")).abs();
            String libelle = texte(l.path("libelle"));
            if (date == null || (entree.signum() == 0 && sortie.signum() == 0)) {
                ignorees++;
                continue;
            }
            lignes.add(new LigneReleveRequest(date, libelle == null ? "(sans libellé)" : tronquer(libelle, 500),
                tronquer(texte(l.path("reference")), 100), entree, sortie));
        }
        if (ignorees > 0) {
            avertissements.add(ignorees + " ligne(s) sans date ou sans montant ont été ignorées : vérifiez le relevé.");
        }
        BigDecimal ouverture = racine.path("soldeOuverture").isNumber() ? montant(racine.path("soldeOuverture")) : null;
        BigDecimal cloture = racine.path("soldeCloture").isNumber() ? montant(racine.path("soldeCloture")) : null;
        if (ouverture != null && cloture != null) {
            BigDecimal mouvements = lignes.stream().map(l -> l.entree().subtract(l.sortie())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ecart = ouverture.add(mouvements).subtract(cloture);
            if (ecart.signum() != 0) {
                avertissements.add("Solde d'ouverture + opérations − solde de clôture = " + ecart.toPlainString()
                    + " : une opération manque ou a été mal lue, comparez avec le relevé.");
            }
        } else {
            avertissements.add("Soldes d'ouverture ou de clôture introuvables dans le relevé : saisissez-les.");
        }
        String devise = texte(racine.path("devise"));
        if (devise != null && deviseAttendue != null && !devise.equalsIgnoreCase(deviseAttendue)) {
            avertissements.add("Le relevé semble être en " + devise + " alors que le compte est tenu en " + deviseAttendue
                + " : vérifiez que c'est le bon relevé.");
        }
        return new ExtractionReleveResponse(source, devise == null ? null : devise.toUpperCase(Locale.ROOT),
            date(racine.path("periodeDebut")), date(racine.path("periodeFin")), ouverture, cloture, lignes, avertissements);
    }

    // ---------------------------------------------------------------------

    private static String typeDocument(String nom, String contentType) {
        String n = nom.toLowerCase(Locale.ROOT);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf") || ct.contains("pdf")) return "pdf";
        if (ct.startsWith("image/") || n.matches(".*\\.(png|jpe?g|webp|gif)$")) return "image";
        if (n.matches(".*\\.(xlsx|xls)$") || ct.contains("spreadsheet") || ct.contains("excel")) return "tableur";
        if (n.matches(".*\\.(csv|txt)$") || ct.startsWith("text/")) return "texte";
        throw new IllegalArgumentException("Format non pris en charge : PDF, image (PNG, JPEG, WEBP), Excel ou CSV.");
    }

    private static String typeImage(String nom, String contentType) {
        if (contentType != null && contentType.startsWith("image/")) return contentType;
        String n = nom.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static String texteClasseur(byte[] contenu) throws IOException {
        StringBuilder sb = new StringBuilder();
        DataFormatter format = new DataFormatter(Locale.FRANCE);
        int lignes = 0;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(contenu))) {
            for (Sheet feuille : wb) {
                sb.append("### Feuille ").append(feuille.getSheetName()).append('\n');
                for (Row row : feuille) {
                    List<String> cellules = new ArrayList<>();
                    for (Cell c : row) cellules.add(format.formatCellValue(c).replace('\t', ' ').replace('\n', ' '));
                    if (cellules.stream().allMatch(String::isBlank)) continue;
                    sb.append(String.join("\t", cellules)).append('\n');
                    if (++lignes >= LIGNES_TABLEUR_MAX) return sb.toString();
                }
            }
        }
        return sb.toString();
    }

    private static LocalDate date(JsonNode n) {
        String t = texte(n);
        if (t == null) return null;
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal montant(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return BigDecimal.ZERO;
        BigDecimal v = n.isNumber() ? n.decimalValue() : parse(n.asText());
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal parse(String t) {
        try {
            return new BigDecimal(t.replaceAll("[\\s\\u00A0\\u202F]", "").replace(',', '.'));
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String texte(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String t = n.asText().strip();
        return t.isEmpty() || "null".equalsIgnoreCase(t) ? null : t;
    }

    private static String tronquer(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static final Map<String, Object> NULLABLE_TEXTE = Map.of("type", List.of("string", "null"));
    private static final Map<String, Object> NULLABLE_NOMBRE = Map.of("type", List.of("number", "null"));

    /** Schéma JSON strict de la réponse (toutes les clés requises, valeurs nulles permises). */
    static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("devise", "periodeDebut", "periodeFin", "soldeOuverture", "soldeCloture", "lignes"),
        "properties", Map.of(
            "devise", NULLABLE_TEXTE,
            "periodeDebut", NULLABLE_TEXTE,
            "periodeFin", NULLABLE_TEXTE,
            "soldeOuverture", NULLABLE_NOMBRE,
            "soldeCloture", NULLABLE_NOMBRE,
            "lignes", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of("date", "libelle", "reference", "entree", "sortie"),
                    "properties", Map.of(
                        "date", Map.of("type", "string"),
                        "libelle", Map.of("type", "string"),
                        "reference", NULLABLE_TEXTE,
                        "entree", Map.of("type", "number"),
                        "sortie", Map.of("type", "number"))))));
}

package com.mbsc.finapp.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.mbsc.finapp.domain.FactureNormalisee;
import com.mbsc.finapp.domain.GroupeTaxeDgi;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.FactureNormaliseeRepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.service.ThemeDocumentService.ThemeDocument;
import com.mbsc.finapp.service.emcf.FactureNormaliseeMapper;
import com.mbsc.finapp.service.emcf.ParametresEmcfService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mbsc.finapp.service.EnteteDocumentRendu.BOLD;
import static com.mbsc.finapp.service.EnteteDocumentRendu.ITAL;
import static com.mbsc.finapp.service.EnteteDocumentRendu.REG;

/**
 * Facture de vente (ou avoir) au format PDF A4 : papier à en-tête du thème, client avec NIF, lignes par groupe de
 * taxation, totaux par groupe et bloc fiscal (UID, signature, dispositif, date fiscale) avec le code QR renvoyé par
 * le dispositif. Une facture non certifiée porte un bandeau « NON CERTIFIÉE », une facture de simulation un bandeau
 * « SANS VALEUR FISCALE » : elles ne peuvent pas passer pour une facture normalisée.
 */
@Service
@RequiredArgsConstructor
public class VentePdfService {

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGE = 36f;
    private static final float LARGEUR = PAGE.getWidth() - 2 * MARGE;
    private static final float[] GRIS = {0.42f, 0.45f, 0.5f};
    private static final float[] GRIS_CLAIR = {0.95f, 0.96f, 0.97f};
    private static final float[] ROUGE = {0.75f, 0.1f, 0.1f};
    private static final float[] NOIR = {0.07f, 0.09f, 0.15f};
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final float TAILLE_QR = 92f;

    private final VenteRepository ventes;
    private final FactureNormaliseeRepository factures;
    private final ParametresEmcfService parametres;
    private final ThemeDocumentService themeService;

    public record Document(byte[] contenu, String nomFichier) {}

    @Transactional(readOnly = true)
    public Document generer(Long venteId, FactureNormalisee.Type type) {
        Vente vente = ventes.findById(venteId).orElseThrow(() -> RessourceIntrouvableException.of("Vente", venteId));
        FactureNormalisee f = factures.findByVenteIdAndType(venteId, type).orElse(null);
        if (type == FactureNormalisee.Type.AVOIR && f == null) {
            throw new RessourceIntrouvableException("Aucun avoir pour cette vente.");
        }
        List<GroupeTaxeDgi> groupes = parametres.groupes();
        ThemeDocument theme = themeService.theme();
        try (PDDocument doc = new PDDocument()) {
            Rendu r = new Rendu(doc, theme, vente, f, type, groupes);
            r.rendre();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            String nom = (type == FactureNormalisee.Type.AVOIR ? "avoir-" : "facture-") + vente.getReference() + ".pdf";
            return new Document(out.toByteArray(), nom);
        } catch (IOException e) {
            throw new IllegalStateException("Génération du PDF de la facture impossible : " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------

    private static final class Rendu {
        private final PDDocument doc;
        private final ThemeDocument theme;
        private final Vente vente;
        private final FactureNormalisee fiscale;
        private final FactureNormalisee.Type type;
        private final Map<String, GroupeTaxeDgi> groupes = new LinkedHashMap<>();
        private final EnteteDocumentRendu papier;
        private final float[] couleur;
        private final DecimalFormat montant;
        private PDPageContentStream cs;
        private float y;
        private float basContenu;
        private float hauteurPied;

        Rendu(PDDocument doc, ThemeDocument theme, Vente vente, FactureNormalisee fiscale, FactureNormalisee.Type type,
              List<GroupeTaxeDgi> liste) {
            this.doc = doc;
            this.theme = theme;
            this.vente = vente;
            this.fiscale = fiscale;
            this.type = type;
            liste.forEach(g -> groupes.put(g.getCode(), g));
            this.papier = new EnteteDocumentRendu(doc, theme, MARGE);
            this.couleur = theme.couleur();
            DecimalFormatSymbols s = new DecimalFormatSymbols();
            s.setGroupingSeparator(' ');
            s.setDecimalSeparator(',');
            this.montant = new DecimalFormat("#,##0.00", s);
        }

        private boolean certifiee() {
            return fiscale != null && fiscale.getStatut() == FactureNormalisee.Statut.CERTIFIEE;
        }

        void rendre() throws IOException {
            nouvellePage();
            titre();
            bandeauStatut();
            client();
            lignes();
            totaux();
            blocFiscal();
            if (cs != null) cs.close();
            pagination();
        }

        private void nouvellePage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PAGE);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            float hauteurEntete = papier.entete(cs, PAGE, doc.getNumberOfPages() == 1);
            hauteurPied = papier.pied(cs, PAGE);
            basContenu = hauteurPied + 24f;
            y = PAGE.getHeight() - hauteurEntete - 14;
        }

        private void place(float h) throws IOException {
            if (y - h < basContenu) nouvellePage();
        }

        private void titre() throws IOException {
            boolean avoir = type == FactureNormalisee.Type.AVOIR;
            texte(avoir ? "AVOIR (FACTURE D'AVOIR)" : "FACTURE NORMALISÉE", BOLD, 16, MARGE, y - 14, couleur);
            texteDroite("N° " + vente.getReference(), BOLD, 10, PAGE.getWidth() - MARGE, y - 10, NOIR);
            LocalDate d = fiscale != null && fiscale.getDateFiscale() != null
                ? fiscale.getDateFiscale().atZone(ZoneId.systemDefault()).toLocalDate() : vente.getDateVente();
            texteDroite("Date : " + d.format(JOUR) + "   Devise : " + vente.getDevise().name(), REG, 8.5f, PAGE.getWidth() - MARGE, y - 22, GRIS);
            if (avoir && fiscale != null && fiscale.getOrigine() != null && fiscale.getOrigine().getUid() != null) {
                texte("Annule la facture UID " + fiscale.getOrigine().getUid(), ITAL, 8, MARGE, y - 28, GRIS);
            }
            cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
            cs.addRect(MARGE, y - 36, LARGEUR, 1.2f);
            cs.fill();
            y -= 48;
        }

        private void bandeauStatut() throws IOException {
            String msg = null;
            if (fiscale == null || fiscale.getStatut() != FactureNormalisee.Statut.CERTIFIEE) {
                msg = "NON CERTIFIÉE - NE VAUT PAS FACTURE NORMALISÉE";
                if (fiscale != null && fiscale.getStatut() == FactureNormalisee.Statut.EN_ATTENTE) msg += " (transmission en attente)";
            } else if ("SIMULATION".equals(fiscale.getMode())) {
                msg = "SIMULATION - SANS VALEUR FISCALE";
            }
            if (msg == null) return;
            cs.setNonStrokingColor(ROUGE[0], ROUGE[1], ROUGE[2]);
            cs.addRect(MARGE, y - 16, LARGEUR, 18);
            cs.fill();
            float w = EnteteDocumentRendu.largeur(EnteteDocumentRendu.propre(msg, BOLD), BOLD, 9);
            texte(msg, BOLD, 9, MARGE + (LARGEUR - w) / 2, y - 11, new float[] {1, 1, 1});
            y -= 28;
        }

        private void client() throws IOException {
            var c = vente.getClient();
            cs.setNonStrokingColor(GRIS_CLAIR[0], GRIS_CLAIR[1], GRIS_CLAIR[2]);
            cs.addRect(MARGE, y - 44, LARGEUR, 48);
            cs.fill();
            texte("CLIENT", BOLD, 7.5f, MARGE + 8, y - 8, GRIS);
            texte(vente.designationClient(), BOLD, 10.5f, MARGE + 8, y - 21, NOIR);
            String detail = (c != null && c.getNif() != null ? "NIF : " + c.getNif() + "   " : "")
                + (c != null && c.getAdresse() != null ? c.getAdresse() : "");
            if (!detail.isBlank()) texte(detail, REG, 8.5f, MARGE + 8, y - 33, GRIS);
            texteDroite("Règlement : " + vente.getModeReglement().name().toLowerCase().replace('_', ' '),
                REG, 8.5f, PAGE.getWidth() - MARGE - 8, y - 21, GRIS);
            y -= 58;
        }

        // Colonnes : désignation | qté | prix unitaire | groupe | HT | TVA | TTC
        private final float[] X = {0, 0, 0, 0, 0, 0, 0};

        private void colonnes() {
            X[0] = MARGE + 4;
            X[1] = MARGE + 250;   // droite
            X[2] = MARGE + 318;   // droite
            X[3] = MARGE + 336;   // gauche (groupe)
            X[4] = MARGE + 410;   // droite
            X[5] = MARGE + 468;   // droite
            X[6] = MARGE + LARGEUR - 4; // droite
        }

        private void enteteTableau() throws IOException {
            colonnes();
            cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
            cs.addRect(MARGE, y - 15, LARGEUR, 16);
            cs.fill();
            float[] b = {1, 1, 1};
            texte("Désignation", BOLD, 8, X[0], y - 10, b);
            texteDroite("Qté", BOLD, 8, X[1], y - 10, b);
            texteDroite("P.U.", BOLD, 8, X[2] + 40, y - 10, b);
            texte("Gr.", BOLD, 8, X[3] + 30, y - 10, b);
            texteDroite("HT", BOLD, 8, X[4], y - 10, b);
            texteDroite("TVA", BOLD, 8, X[5], y - 10, b);
            texteDroite("TTC", BOLD, 8, X[6], y - 10, b);
            y -= 18;
        }

        private void lignes() throws IOException {
            enteteTableau();
            int i = 0;
            for (LigneVente l : vente.getLignes()) {
                int pages = doc.getNumberOfPages();
                place(16);
                if (doc.getNumberOfPages() != pages) enteteTableau();
                if (i % 2 == 1) {
                    cs.setNonStrokingColor(GRIS_CLAIR[0], GRIS_CLAIR[1], GRIS_CLAIR[2]);
                    cs.addRect(MARGE, y - 12, LARGEUR, 14);
                    cs.fill();
                }
                BigDecimal ttc = l.getMontantHt().add(l.getMontantTva());
                texte(tronquer(l.getDesignation(), REG, 8.5f, 235), REG, 8.5f, X[0], y - 8, NOIR);
                texteDroite(l.getQuantite().stripTrailingZeros().toPlainString(), REG, 8.5f, X[1], y - 8, NOIR);
                texteDroite(montant.format(l.getPrixUnitaire()), REG, 8.5f, X[2] + 40, y - 8, NOIR);
                texte(FactureNormaliseeMapper.groupe(l), BOLD, 8.5f, X[3] + 30, y - 8, NOIR);
                texteDroite(montant.format(l.getMontantHt()), REG, 8.5f, X[4], y - 8, NOIR);
                texteDroite(montant.format(l.getMontantTva()), REG, 8.5f, X[5], y - 8, NOIR);
                texteDroite(montant.format(ttc), BOLD, 8.5f, X[6], y - 8, NOIR);
                y -= 15;
                i++;
            }
            y -= 6;
        }

        private void totaux() throws IOException {
            Map<String, BigDecimal[]> par = new LinkedHashMap<>();
            for (LigneVente l : vente.getLignes()) {
                BigDecimal[] t = par.computeIfAbsent(FactureNormaliseeMapper.groupe(l), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                t[0] = t[0].add(l.getMontantHt());
                t[1] = t[1].add(l.getMontantTva());
            }
            place(24 + par.size() * 13 + 40);
            texte("Totaux par groupe de taxation", BOLD, 8.5f, MARGE + 4, y - 8, GRIS);
            y -= 16;
            for (var e : par.entrySet()) {
                GroupeTaxeDgi g = groupes.get(e.getKey());
                String lib = "Groupe " + e.getKey() + (g != null ? " - " + g.getLibelle() + " (" + g.getTaux().stripTrailingZeros().toPlainString() + " %)" : "");
                texte(lib, REG, 8.5f, MARGE + 4, y - 8, NOIR);
                texteDroite("HT " + montant.format(e.getValue()[0]), REG, 8.5f, X[4] + 20, y - 8, NOIR);
                texteDroite("TVA " + montant.format(e.getValue()[1]), REG, 8.5f, X[6], y - 8, NOIR);
                y -= 13;
            }
            y -= 4;
            cs.setNonStrokingColor(couleur[0], couleur[1], couleur[2]);
            cs.addRect(MARGE + LARGEUR - 230, y - 40, 230, 44);
            cs.fill();
            float[] b = {1, 1, 1};
            texte("Total HT", REG, 9, MARGE + LARGEUR - 222, y - 10, b);
            texteDroite(montant.format(vente.getTotalHt()), REG, 9, MARGE + LARGEUR - 8, y - 10, b);
            texte("TVA", REG, 9, MARGE + LARGEUR - 222, y - 22, b);
            texteDroite(montant.format(vente.getTotalTva()), REG, 9, MARGE + LARGEUR - 8, y - 22, b);
            texte("TOTAL TTC (" + vente.getDevise().name() + ")", BOLD, 10.5f, MARGE + LARGEUR - 222, y - 35, b);
            texteDroite(montant.format(vente.getTotalTtc()), BOLD, 10.5f, MARGE + LARGEUR - 8, y - 35, b);
            y -= 56;
        }

        private void blocFiscal() throws IOException {
            place(TAILLE_QR + 34);
            cs.setStrokingColor(0.86f, 0.87f, 0.89f);
            cs.addRect(MARGE, y - TAILLE_QR - 22, LARGEUR, TAILLE_QR + 26);
            cs.stroke();
            texte("INFORMATIONS FISCALES (DGI)", BOLD, 8, MARGE + 8, y - 10, couleur);
            if (!certifiee()) {
                texte("Cette facture n'a pas (encore) été certifiée par le dispositif de facturation de la DGI.", ITAL, 8.5f, MARGE + 8, y - 26, ROUGE);
                if (fiscale != null && fiscale.getDerniereErreur() != null) {
                    texte(tronquer("Motif : " + fiscale.getDerniereErreur(), REG, 8, LARGEUR - 16), REG, 8, MARGE + 8, y - 40, GRIS);
                }
                y -= TAILLE_QR + 34;
                return;
            }
            float yy = y - 26;
            yy = ligneFiscale("UID", fiscale.getUid(), yy);
            yy = ligneFiscale("Signature", fiscale.getSignature(), yy);
            yy = ligneFiscale("Dispositif (DEF)", fiscale.getNumeroDef(), yy);
            yy = ligneFiscale("Date et heure fiscales",
                fiscale.getDateFiscale() == null ? null : fiscale.getDateFiscale().atZone(ZoneId.systemDefault()).format(HEURE), yy);
            if (fiscale.getCodeQr() != null && !fiscale.getCodeQr().isBlank()) {
                qr(fiscale.getCodeQr(), MARGE + LARGEUR - TAILLE_QR - 8, y - TAILLE_QR - 14);
            }
            y -= TAILLE_QR + 34;
        }

        private float ligneFiscale(String libelle, String valeur, float yy) throws IOException {
            texte(libelle + " :", BOLD, 8.5f, MARGE + 8, yy, GRIS);
            texte(tronquer(valeur == null ? "-" : valeur, REG, 8.5f, LARGEUR - TAILLE_QR - 150), REG, 8.5f, MARGE + 118, yy, NOIR);
            return yy - 14;
        }

        /** Dessine le code QR en vectoriel (modules pleins), sans passer par une image. */
        private void qr(String contenu, float x, float yBas) throws IOException {
            try {
                BitMatrix m = new QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
                float cellule = TAILLE_QR / m.getWidth();
                cs.setNonStrokingColor(0f, 0f, 0f);
                for (int i = 0; i < m.getHeight(); i++) {
                    for (int j = 0; j < m.getWidth(); j++) {
                        if (m.get(j, i)) {
                            cs.addRect(x + j * cellule, yBas + TAILLE_QR - (i + 1) * cellule, cellule + 0.15f, cellule + 0.15f);
                        }
                    }
                }
                cs.fill();
            } catch (com.google.zxing.WriterException e) {
                throw new IOException("Code QR impossible : " + e.getMessage(), e);
            }
        }

        private void pagination() throws IOException {
            int total = doc.getNumberOfPages();
            if (total < 2) return;
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream p = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    cs = p;
                    texteDroite("Page " + (i + 1) + " / " + total, REG, 7, PAGE.getWidth() - MARGE, hauteurPied + 8, GRIS);
                }
            }
            cs = null;
        }

        // ---------------- texte ----------------

        private void texte(String s, PDFont f, float taille, float x, float yy, float[] c) throws IOException {
            cs.beginText();
            cs.setNonStrokingColor(c[0], c[1], c[2]);
            cs.setFont(f, taille);
            cs.newLineAtOffset(x, yy);
            cs.showText(EnteteDocumentRendu.propre(s, f));
            cs.endText();
        }

        private void texteDroite(String s, PDFont f, float taille, float xDroit, float yy, float[] c) throws IOException {
            String p = EnteteDocumentRendu.propre(s, f);
            texte(s, f, taille, xDroit - EnteteDocumentRendu.largeur(p, f, taille), yy, c);
        }

        private static String tronquer(String s, PDFont f, float taille, float max) throws IOException {
            String p = EnteteDocumentRendu.propre(s == null ? "" : s, f);
            if (EnteteDocumentRendu.largeur(p, f, taille) <= max) return p;
            while (p.length() > 1 && EnteteDocumentRendu.largeur(p + "...", f, taille) > max) p = p.substring(0, p.length() - 1);
            return p + "...";
        }
    }
}

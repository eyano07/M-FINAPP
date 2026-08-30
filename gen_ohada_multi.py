#!/usr/bin/env python3
"""Etats financiers OHADA revise (SYSCOHADA) - FR + EN.
Balance a 6 colonnes (ouverture / mouvements / cloture).
Formules chainees Journal -> Balance -> Resultat(SIG)/Bilan -> TFT -> Ratios."""
import zipfile
from xml.sax.saxutils import escape

LANG = "fr"
def tr(fr, en): return fr if LANG == "fr" else en

def col_letter(idx):
    s=""; idx+=1
    while idx:
        idx,rem=divmod(idx-1,26); s=chr(65+rem)+s
    return s
def C(kind,value,style): return (kind,value,style)
def render_cell(ref,cd):
    kind,value,style=cd
    if kind=="blank" or value is None or value=="":
        return f'<c r="{ref}" s="{style}"/>'
    if kind=="n":
        return f'<c r="{ref}" s="{style}"><v>{value}</v></c>'
    if kind=="f":
        return f'<c r="{ref}" s="{style}"><f>{escape(value)}</f></c>'
    return f'<c r="{ref}" s="{style}" t="inlineStr"><is><t xml:space="preserve">{escape(str(value))}</t></is></c>'
def render_sheet(rows,widths,freeze_row=1,merges=None,drawing_rid=None,row_heights=None):
    p=['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
       '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">']
    if freeze_row:
        p.append(f'<sheetViews><sheetView workbookViewId="0"><pane ySplit="{freeze_row}" topLeftCell="A{freeze_row+1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>')
    if widths:
        p.append('<cols>')
        for i,w in enumerate(widths):
            p.append(f'<col min="{i+1}" max="{i+1}" width="{w}" customWidth="1"/>')
        p.append('</cols>')
    p.append('<sheetData>')
    for ri,row in enumerate(rows,start=1):
        ht=f' ht="{row_heights[ri]}" customHeight="1"' if row_heights and ri in row_heights else ""
        p.append(f'<row r="{ri}"{ht}>')
        for ci,cd in enumerate(row):
            if cd is None: continue
            p.append(render_cell(f"{col_letter(ci)}{ri}",cd))
        p.append('</row>')
    p.append('</sheetData>')
    if merges:
        p.append(f'<mergeCells count="{len(merges)}">')
        for m in merges: p.append(f'<mergeCell ref="{m}"/>')
        p.append('</mergeCells>')
    if drawing_rid:
        p.append(f'<drawing r:id="{drawing_rid}"/>')
    p.append('</worksheet>')
    return "".join(p)

TITLE=8; HDR=1; TXT=2; BTX=3; MON=4; BMON=5; PCT=6; RAT=7; CODE=9; NTXT=10

# (date, piece, code, (compte_fr,compte_en), (lib_fr,lib_en), debit, credit)
def JE():
    O=("Solde d'ouverture","Opening balance")
    return [
     ("01/01/2025","OUV",24,("Immobilisations corporelles","Property, plant and equipment"),O,200000,0),
     ("01/01/2025","OUV",31,("Stocks de marchandises","Merchandise inventory"),O,40000,0),
     ("01/01/2025","OUV",411,("Clients","Trade receivables"),O,30000,0),
     ("01/01/2025","OUV",52,("Banques","Banks"),O,50000,0),
     ("01/01/2025","OUV",28,("Amortissements","Accumulated depreciation"),O,0,60000),
     ("01/01/2025","OUV",401,("Fournisseurs","Trade payables"),O,0,25000),
     ("01/01/2025","OUV",16,("Emprunts","Borrowings"),O,0,80000),
     ("01/01/2025","OUV",101,("Capital social","Share capital"),O,0,100000),
     ("01/01/2025","OUV",11,("Reserves","Reserves"),O,0,40000),
     ("01/01/2025","OUV",12,("Report a nouveau","Retained earnings"),O,0,15000),
     ("15/02/2025","VT-01",411,("Clients","Trade receivables"),("Ventes a credit","Credit sales"),300000,0),
     ("15/02/2025","VT-01",701,("Ventes de marchandises","Sales of goods"),("Ventes a credit","Credit sales"),0,300000),
     ("20/03/2025","ENC-01",52,("Banques","Banks"),("Encaissement clients","Cash collected from customers"),290000,0),
     ("20/03/2025","ENC-01",411,("Clients","Trade receivables"),("Encaissement clients","Cash collected from customers"),0,290000),
     ("05/04/2025","ACH-01",601,("Achats de marchandises","Purchases of goods"),("Achats a credit","Credit purchases"),160000,0),
     ("05/04/2025","ACH-01",401,("Fournisseurs","Trade payables"),("Achats a credit","Credit purchases"),0,160000),
     ("10/07/2025","PAY-01",401,("Fournisseurs","Trade payables"),("Reglement fournisseurs","Payment to suppliers"),165000,0),
     ("10/07/2025","PAY-01",52,("Banques","Banks"),("Reglement fournisseurs","Payment to suppliers"),0,165000),
     ("31/07/2025","SAL-01",66,("Charges de personnel","Personnel expenses"),("Salaires","Salaries"),60000,0),
     ("31/07/2025","SAL-01",52,("Banques","Banks"),("Salaires","Salaries"),0,60000),
     ("31/08/2025","SE-01",62,("Services exterieurs","External services"),("Prestations externes","External services rendered"),25000,0),
     ("31/08/2025","SE-01",52,("Banques","Banks"),("Prestations externes","External services rendered"),0,25000),
     ("15/10/2025","INV-01",24,("Immobilisations corporelles","Property, plant and equipment"),("Acquisition materiel","Equipment purchase"),30000,0),
     ("15/10/2025","INV-01",52,("Banques","Banks"),("Acquisition materiel","Equipment purchase"),0,30000),
     ("31/10/2025","FIN-01",671,("Charges financieres","Financial expenses"),("Interets emprunt","Loan interest"),8000,0),
     ("31/10/2025","FIN-01",52,("Banques","Banks"),("Interets emprunt","Loan interest"),0,8000),
     ("01/11/2025","EMP-01",52,("Banques","Banks"),("Nouvel emprunt","New loan"),20000,0),
     ("01/11/2025","EMP-01",16,("Emprunts","Borrowings"),("Nouvel emprunt","New loan"),0,20000),
     ("15/12/2025","IS-01",891,("Impots sur le resultat","Income tax"),("Charge d'impot","Income tax charge"),12000,0),
     ("15/12/2025","IS-01",44,("Etat, impot sur benefices","State, income tax"),("Charge d'impot","Income tax charge"),0,12000),
     ("20/12/2025","IS-02",44,("Etat, impot sur benefices","State, income tax"),("Paiement acompte","Tax installment payment"),9000,0),
     ("20/12/2025","IS-02",52,("Banques","Banks"),("Paiement acompte","Tax installment payment"),0,9000),
     ("28/12/2025","DIV-01",12,("Report a nouveau","Retained earnings"),("Dividendes verses","Dividends paid"),15000,0),
     ("28/12/2025","DIV-01",52,("Banques","Banks"),("Dividendes verses","Dividends paid"),0,15000),
     ("31/12/2025","AM-01",681,("Dotations aux amortissements","Depreciation allowances"),("Dotation exercice","Period depreciation"),20000,0),
     ("31/12/2025","AM-01",28,("Amortissements","Accumulated depreciation"),("Dotation exercice","Period depreciation"),0,20000),
     ("31/12/2025","VS-01",6031,("Variation de stocks de marchandises","Change in merchandise inventory"),("Destockage net","Net inventory decrease"),10000,0),
     ("31/12/2025","VS-01",31,("Stocks de marchandises","Merchandise inventory"),("Destockage net","Net inventory decrease"),0,10000),
    ]

def build_journal():
    entries=JE()
    rows=[[C("s",tr("JOURNAL COMPTABLE OHADA - Exercice 2025","OHADA GENERAL JOURNAL - Year 2025"),TITLE)]]
    hdr=[tr("Date","Date"),tr("Piece","Ref"),tr("Compte","Account"),
         tr("Intitule","Account name"),tr("Libelle ecriture","Entry description"),
         tr("Debit","Debit"),tr("Credit","Credit"),tr("Mois","Month")]
    rows.append([C("s",h,HDR) for h in hdr])
    first=3
    for i,(date,piece,code,compte,lib,deb,cred) in enumerate(entries):
        r=first+i
        rows.append([C("s",date,TXT),C("s",piece,TXT),C("n",code,CODE),
                     C("s",tr(*compte),TXT),C("s",tr(*lib),TXT),
                     C("n",deb,MON) if deb else C("blank","",MON),
                     C("n",cred,MON) if cred else C("blank","",MON),
                     C("f",f"VALUE(MID(A{r},4,2))",CODE)])
    last=first+len(entries)-1
    rows.append([C("blank","",TXT)]*4+[C("s",tr("TOTAUX","TOTALS"),BTX),
                 C("f",f"SUM(F{first}:F{last})",BMON),C("f",f"SUM(G{first}:G{last})",BMON),
                 C("blank","",TXT)])
    trn=last+1
    rows.append([C("blank","",TXT)]*4+[C("s",tr("Controle equilibre","Balance check"),BTX),
                 C("f",f'IF(F{trn}=G{trn},"{tr("Equilibre","Balanced")}","{tr("Desequilibre","Unbalanced")}")',BTX),
                 C("blank","",TXT),C("blank","",TXT)])
    return render_sheet(rows,[12,10,10,34,30,14,14,8],freeze_row=2,merges=["A1:H1"])

ACCOUNTS=[
 (101,("Capital social","Share capital")),(11,("Reserves","Reserves")),
 (12,("Report a nouveau","Retained earnings")),(16,("Emprunts","Borrowings")),
 (24,("Immobilisations corporelles (brut)","PP&E (gross)")),
 (28,("Amortissements","Accumulated depreciation")),
 (31,("Stocks de marchandises","Merchandise inventory")),
 (401,("Fournisseurs","Trade payables")),(411,("Clients","Trade receivables")),
 (44,("Etat, impot sur benefices","State, income tax")),(52,("Banques","Banks")),
 (601,("Achats de marchandises","Purchases of goods")),
 (6031,("Variation de stocks de marchandises","Change in inventory")),
 (62,("Services exterieurs","External services")),
 (66,("Charges de personnel","Personnel expenses")),
 (671,("Charges financieres","Financial expenses")),
 (681,("Dotations aux amortissements","Depreciation allowances")),
 (701,("Ventes de marchandises","Sales of goods")),
 (891,("Impots sur le resultat","Income tax")),
]
BR={code:i+3 for i,(code,_) in enumerate(ACCOUNTS)}

def build_balance():
    """Balance a 6 colonnes : Ouverture(C,D) / Mouvements(E,F) / Solde cloture(G,H)."""
    rows=[[C("s",tr("BALANCE OHADA A 6 COLONNES - Exercice 2025","OHADA 6-COLUMN TRIAL BALANCE - Year 2025"),TITLE)]]
    hdr=[tr("Compte","Account"),tr("Intitule","Account name"),
         tr("Ouv. Debit","Opening Debit"),tr("Ouv. Credit","Opening Credit"),
         tr("Mouv. Debit","Movements Debit"),tr("Mouv. Credit","Movements Credit"),
         tr("Solde Debiteur","Closing Debit"),tr("Solde Crediteur","Closing Credit")]
    rows.append([C("s",h,HDR) for h in hdr])
    first=3
    for code,lib in ACCOUNTS:
        r=len(rows)+1
        rows.append([
            C("n",code,CODE),C("s",tr(*lib),TXT),
            C("f",f'SUMIFS(Journal!$F:$F,Journal!$C:$C,A{r},Journal!$B:$B,"OUV")',MON),
            C("f",f'SUMIFS(Journal!$G:$G,Journal!$C:$C,A{r},Journal!$B:$B,"OUV")',MON),
            C("f",f'SUMIFS(Journal!$F:$F,Journal!$C:$C,A{r},Journal!$B:$B,"<>OUV")',MON),
            C("f",f'SUMIFS(Journal!$G:$G,Journal!$C:$C,A{r},Journal!$B:$B,"<>OUV")',MON),
            C("f",f"IF((C{r}+E{r})-(D{r}+F{r})>0,(C{r}+E{r})-(D{r}+F{r}),0)",MON),
            C("f",f"IF((D{r}+F{r})-(C{r}+E{r})>0,(D{r}+F{r})-(C{r}+E{r}),0)",MON),
        ])
    last=first+len(ACCOUNTS)-1
    rows.append([C("blank","",TXT),C("s",tr("TOTAUX","TOTALS"),BTX)]+
                [C("f",f"SUM({col}{first}:{col}{last})",BMON) for col in ["C","D","E","F","G","H"]])
    return render_sheet(rows,[9,32,13,13,14,14,14,14],freeze_row=2,merges=["A1:H1"])

# Soldes de cloture : debiteur = colonne G, crediteur = colonne H
def SD(code): return f"Balance!G{BR[code]}"
def SC(code): return f"Balance!H{BR[code]}"

def build_resultat():
    rows=[[C("s",tr("COMPTE DE RESULTAT OHADA (Soldes Intermediaires de Gestion) - 2025",
                    "OHADA INCOME STATEMENT (Intermediate Management Balances) - 2025"),TITLE)]]
    rows.append([C("s",tr("Poste","Item"),HDR),C("s",tr("Montant","Amount"),HDR)])
    data=[
     (tr("Ventes de marchandises","Sales of goods"), f"{SC(701)}", MON),
     (tr("Achats de marchandises","Purchases of goods"), f"-{SD(601)}", MON),
     (tr("Variation de stocks de marchandises","Change in merchandise inventory"), f"-({SD(6031)}-{SC(6031)})", MON),
     (tr("MARGE COMMERCIALE","GROSS TRADING MARGIN"), "B3+B4+B5", BMON),
     (tr("Services exterieurs","External services"), f"-{SD(62)}", MON),
     (tr("VALEUR AJOUTEE","VALUE ADDED"), "B6+B7", BMON),
     (tr("Charges de personnel","Personnel expenses"), f"-{SD(66)}", MON),
     (tr("EXCEDENT BRUT D'EXPLOITATION (EBE)","GROSS OPERATING SURPLUS (EBITDA)"), "B8+B9", BMON),
     (tr("Dotations aux amortissements","Depreciation allowances"), f"-{SD(681)}", MON),
     (tr("RESULTAT D'EXPLOITATION","OPERATING PROFIT"), "B10+B11", BMON),
     (tr("Charges financieres","Financial expenses"), f"-{SD(671)}", MON),
     (tr("RESULTAT FINANCIER","FINANCIAL RESULT"), "B13", BMON),
     (tr("RESULTAT DES ACTIVITES ORDINAIRES","PROFIT FROM ORDINARY ACTIVITIES"), "B12+B14", BMON),
     (tr("Resultat hors activites ordinaires (HAO)","Result from extraordinary items"), "0", MON),
     (tr("RESULTAT AVANT IMPOT","PROFIT BEFORE TAX"), "B15+B16", BMON),
     (tr("Impots sur le resultat","Income tax"), f"-{SD(891)}", MON),
     (tr("RESULTAT NET DE L'EXERCICE","NET PROFIT FOR THE YEAR"), "B17+B18", BMON),
    ]
    for lib,formula,st in data:
        rows.append([C("s",lib,BTX if st==BMON else TXT),C("f",formula,st)])
    return render_sheet(rows,[48,18],freeze_row=2,merges=["A1:B1"])

def build_bilan():
    rows=[[C("s",tr("BILAN OHADA au 31/12/2025","OHADA BALANCE SHEET as at 31/12/2025"),TITLE)]]
    rows.append([C("s",tr("ACTIF","ASSETS"),HDR),C("s",tr("Brut","Gross"),HDR),
                 C("s",tr("Amort./Depr.","Depr./Amort."),HDR),C("s",tr("Net","Net"),HDR)])
    def line(lbl,brut,amort,net,style=MON):
        lbl_s=BTX if style==BMON else TXT
        return [C("s",lbl,lbl_s),
                C("f",brut,style) if brut else C("blank","",style),
                C("f",amort,style) if amort else C("blank","",style),
                C("f",net,style) if net else C("blank","",style)]
    def sec(lbl): return [C("s",lbl,BTX)]+[C("blank","",BTX)]*3
    def pline(lbl,net,style=MON):
        return [C("s",lbl,BTX if style==BMON else TXT),C("blank","",style),C("blank","",style),
                C("f",net,style) if net else C("blank","",style)]
    rows.append(sec(tr("Actif immobilise","Non-current assets")))
    rows.append(line(tr("Immobilisations corporelles","Property, plant and equipment"), f"{SD(24)}", f"{SC(28)}", "B4-C4"))
    rows.append(line(tr("Total actif immobilise","Total non-current assets"),"","","D4",BMON))
    rows.append(sec(tr("Actif circulant","Current assets")))
    rows.append(line(tr("Stocks de marchandises","Merchandise inventory"), f"{SD(31)}", "0", "B7-C7"))
    rows.append(line(tr("Creances clients","Trade receivables"), f"{SD(411)}", "0", "B8-C8"))
    rows.append(line(tr("Total actif circulant","Total current assets"),"","","D7+D8",BMON))
    rows.append(sec(tr("Tresorerie-Actif","Cash assets")))
    rows.append(line(tr("Banques","Banks"), f"{SD(52)}", "0", "B11-C11"))
    rows.append(line(tr("Total tresorerie-actif","Total cash assets"),"","","D11",BMON))
    rows.append(line(tr("TOTAL ACTIF","TOTAL ASSETS"),"B4+B7+B8+B11","C4","D5+D9+D12",BMON))
    rows.append([C("blank","",TXT)]*4)
    rows.append([C("s",tr("PASSIF","EQUITY & LIABILITIES"),HDR),C("blank","",HDR),C("blank","",HDR),C("s",tr("Net","Net"),HDR)])
    rows.append(sec(tr("Capitaux propres","Equity")))
    rows.append(pline(tr("Capital social","Share capital"), f"{SC(101)}"))
    rows.append(pline(tr("Reserves","Reserves"), f"{SC(11)}"))
    rows.append(pline(tr("Report a nouveau","Retained earnings"), f"{SC(12)}-{SD(12)}"))
    rows.append(pline(tr("Resultat net de l'exercice","Net profit for the year"), "Resultat!B19"))
    rows.append(pline(tr("Total capitaux propres","Total equity"), "D17+D18+D19+D20", BMON))
    rows.append(sec(tr("Dettes financieres","Financial liabilities")))
    rows.append(pline(tr("Emprunts","Borrowings"), f"{SC(16)}"))
    rows.append(pline(tr("Total dettes financieres","Total financial liabilities"), "D23", BMON))
    rows.append(sec(tr("Passif circulant","Current liabilities")))
    rows.append(pline(tr("Fournisseurs","Trade payables"), f"{SC(401)}"))
    rows.append(pline(tr("Etat, impot sur benefices","State, income tax"), f"{SC(44)}"))
    rows.append(pline(tr("Total passif circulant","Total current liabilities"), "D26+D27", BMON))
    rows.append(pline(tr("TOTAL PASSIF","TOTAL EQUITY & LIABILITIES"), "D21+D24+D28", BMON))
    rows.append(pline(tr("Controle (Actif - Passif)","Check (Assets - Liabilities)"), "D13-D29", BMON))
    return render_sheet(rows,[42,14,14,14],freeze_row=2,merges=["A1:D1"])

def build_tresorerie():
    rows=[[C("s",tr("TABLEAU DES FLUX DE TRESORERIE OHADA (indirecte) - 2025",
                    "OHADA STATEMENT OF CASH FLOWS (indirect) - 2025"),TITLE)]]
    rows.append([C("s",tr("Poste","Item"),HDR),C("s",tr("Montant","Amount"),HDR),C("blank","",TXT),
                 C("s",tr("Soldes d'ouverture (01/01)","Opening balances (01/01)"),HDR),C("s",tr("Valeur","Value"),HDR)])
    opening=[(tr("Clients","Trade receivables"),30000),(tr("Stocks","Inventory"),40000),
             (tr("Fournisseurs","Trade payables"),25000),(tr("Immobilisations (brut)","PP&E (gross)"),200000),
             (tr("Emprunts","Borrowings"),80000),(tr("Banques","Banks"),50000),
             (tr("Report a nouveau","Retained earnings"),15000)]
    flux=[
     (tr("Flux des activites operationnelles","Operating activities"),"",BTX),
     (tr("Resultat avant impot","Profit before tax"),"Resultat!B17",MON),
     (tr("+ Dotations aux amortissements","+ Depreciation allowances"),f"{SD(681)}",MON),
     (tr("+ Charges financieres","+ Financial expenses"),f"{SD(671)}",MON),
     (tr("Resultat avant variation du BFR","Profit before working capital changes"),"B4+B5+B6",BMON),
     (tr("Variation des clients","Change in receivables"),f"-({SD(411)}-E3)",MON),
     (tr("Variation des stocks","Change in inventory"),f"-({SD(31)}-E4)",MON),
     (tr("Variation des fournisseurs","Change in payables"),f"{SC(401)}-E5",MON),
     (tr("Tresorerie generee par l'exploitation","Cash generated from operations"),"B7+B8+B9+B10",BMON),
     (tr("Interets payes","Interest paid"),f"-{SD(671)}",MON),
     (tr("Impot paye","Income tax paid"),f"-({SD(891)}-{SC(44)})",MON),
     (tr("Flux net operationnel","Net operating cash flow"),"B11+B12+B13",BMON),
     (tr("Flux des activites d'investissement","Investing activities"),"",BTX),
     (tr("Acquisition d'immobilisations","Purchase of PP&E"),f"-({SD(24)}-E6)",MON),
     (tr("Flux net d'investissement","Net investing cash flow"),"B16",BMON),
     (tr("Flux des activites de financement","Financing activities"),"",BTX),
     (tr("Variation des emprunts","Change in borrowings"),f"{SC(16)}-E7",MON),
     (tr("Dividendes verses","Dividends paid"),f"-(E9-({SC(12)}-{SD(12)}))",MON),
     (tr("Flux net de financement","Net financing cash flow"),"B19+B20",BMON),
     (tr("Variation nette de tresorerie","Net change in cash"),"B14+B17+B21",BMON),
     (tr("Tresorerie a l'ouverture","Opening cash"),"E8",MON),
     (tr("Tresorerie a la cloture","Closing cash"),"B23+B22",BMON),
     (tr("Controle (vs bilan)","Check (vs balance sheet)"),f"B24-{SD(52)}",BMON),
    ]
    for i in range(len(flux)):
        lib,formula,st=flux[i]
        if formula=="":
            rc=[C("s",lib,st),C("blank","",st)]
        else:
            rc=[C("s",lib,BTX if st==BMON else TXT),C("f",formula,st)]
        rc.append(C("blank","",TXT))
        if 0<=i<len(opening):
            rc.append(C("s",opening[i][0],TXT)); rc.append(C("n",opening[i][1],MON))
        rows.append(rc)
    return render_sheet(rows,[42,16,3,28,14],freeze_row=2,merges=["A1:E1"])

def build_ratios():
    rows=[[C("s",tr("RATIOS FINANCIERS OHADA - Exercice 2025","OHADA FINANCIAL RATIOS - Year 2025"),TITLE)]]
    rows.append([C("s",tr("Ratio","Ratio"),HDR),C("s",tr("Valeur","Value"),HDR),
                 C("s",tr("Formule / interpretation","Formula / interpretation"),HDR)])
    data=[
     (tr("Liquidite generale","Current ratio"),"(Bilan!D9+Bilan!D12)/Bilan!D28",RAT,
      tr("(Actif circulant + Tresorerie) / Passif circulant","(Current assets + Cash) / Current liabilities")),
     (tr("Liquidite reduite","Quick ratio"),"(Bilan!D9+Bilan!D12-Bilan!D7)/Bilan!D28",RAT,
      tr("hors stocks / Passif circulant","excl. inventory / Current liabilities")),
     (tr("Autonomie financiere","Financial autonomy"),"Bilan!D21/Bilan!D13",PCT,
      tr("Capitaux propres / Total actif","Equity / Total assets")),
     (tr("Endettement (Dettes fin./CP)","Gearing (Debt/Equity)"),"Bilan!D24/Bilan!D21",RAT,
      tr("Emprunts / Capitaux propres","Borrowings / Equity")),
     (tr("Taux de marge commerciale","Gross trading margin rate"),"Resultat!B6/Resultat!B3",PCT,
      tr("Marge commerciale / Ventes","Trading margin / Sales")),
     (tr("Taux de valeur ajoutee","Value added rate"),"Resultat!B8/Resultat!B3",PCT,
      tr("Valeur ajoutee / Ventes","Value added / Sales")),
     (tr("Marge nette","Net margin"),"Resultat!B19/Resultat!B3",PCT,
      tr("Resultat net / Ventes","Net profit / Sales")),
     (tr("Rentabilite des capitaux propres (ROE)","Return on equity (ROE)"),"Resultat!B19/Bilan!D21",PCT,
      tr("Resultat net / Capitaux propres","Net profit / Equity")),
     (tr("Rentabilite de l'actif (ROA)","Return on assets (ROA)"),"Resultat!B19/Bilan!D13",PCT,
      tr("Resultat net / Total actif","Net profit / Total assets")),
     (tr("Rotation des stocks","Inventory turnover"),"-(Resultat!B4+Resultat!B5)/Bilan!D7",RAT,
      tr("Cout d'achat des marchandises vendues / Stocks","Cost of goods sold / Inventory")),
     (tr("Besoin en fonds de roulement","Working capital requirement"),"Bilan!D7+Bilan!D8-Bilan!D26",MON,
      tr("Stocks + Clients - Fournisseurs","Inventory + Receivables - Payables")),
    ]
    for lib,formula,st,note in data:
        rows.append([C("s",lib,TXT),C("f",formula,st),C("s",note,TXT)])
    return render_sheet(rows,[40,14,56],freeze_row=2,merges=["A1:C1"])

def build_notes():
    rows=[]
    section_rows=[]; para_rows=[]
    rows.append([C("s",tr("NOTES ANNEXES AUX ETATS FINANCIERS - Exercice clos le 31/12/2025",
                          "NOTES TO THE FINANCIAL STATEMENTS - Year ended 31/12/2025"),TITLE)])
    rows.append([])

    def section(titre):
        rows.append([C("s",titre,HDR)]); section_rows.append(len(rows))
    def para(txt):
        rows.append([C("s",txt,NTXT)]); para_rows.append(len(rows))

    # NOTE 1
    section(tr("NOTE 1 - PRINCIPES ET METHODES COMPTABLES","NOTE 1 - ACCOUNTING PRINCIPLES AND METHODS"))
    para(tr("Les etats financiers sont etablis conformement au referentiel comptable SYSCOHADA revise. "
            "Ils sont presentes en francs CFA (FCFA) et couvrent l'exercice clos le 31 decembre 2025.",
            "The financial statements are prepared in accordance with the revised SYSCOHADA accounting "
            "framework. They are presented in CFA francs (FCFA) and cover the year ended 31 December 2025."))
    para(tr("Methode generale d'evaluation : cout historique. Les immobilisations corporelles sont amorties "
            "selon le mode lineaire sur leur duree d'utilite estimee. Les stocks sont evalues au cout moyen "
            "pondere (CMP) et deprecies, le cas echeant, a la valeur nette de realisation si celle-ci est "
            "inferieure au cout.",
            "General measurement basis: historical cost. Property, plant and equipment is depreciated on a "
            "straight-line basis over its estimated useful life. Inventories are measured at weighted average "
            "cost (WAC) and written down to net realisable value where lower than cost."))
    rows.append([])

    # NOTE 2 - Immobilisations
    section(tr("NOTE 2 - IMMOBILISATIONS CORPORELLES","NOTE 2 - PROPERTY, PLANT AND EQUIPMENT"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Brut ouv.","Gross opening"),
                 tr("Acquisitions","Additions"),tr("Cessions","Disposals"),tr("Brut clot.","Gross closing"),
                 tr("Amort. ouv.","Depr. opening"),tr("Dotations","Charge for year"),
                 tr("Amort. clot.","Depr. closing"),tr("VNC clot.","NBV closing")]])
    r=len(rows)+1
    rows.append([C("s",tr("Immobilisations corporelles","Property, plant and equipment"),TXT),
                 C("f","Balance!C7",MON),C("f","Balance!E7",MON),C("f","Balance!F7",MON),C("f","Balance!G7",MON),
                 C("f","Balance!D8",MON),C("f","Balance!F8",MON),C("f","Balance!H8",MON),
                 C("f",f"E{r}-H{r}",BMON)])
    rows.append([])

    # NOTE 3 - Stocks
    section(tr("NOTE 3 - STOCKS","NOTE 3 - INVENTORIES"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Ouverture","Opening"),
                 tr("Destockage net","Net decrease"),tr("Cloture","Closing")]])
    rows.append([C("s",tr("Stocks de marchandises","Merchandise inventory"),TXT),
                 C("f","Balance!C9",MON),C("f","Balance!F9",MON),C("f","Balance!G9",MON)])
    rows.append([])

    # NOTE 4 - Creances clients
    section(tr("NOTE 4 - CREANCES CLIENTS","NOTE 4 - TRADE RECEIVABLES"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Ouverture","Opening"),
                 tr("Ventes exercice","Sales for year"),tr("Encaissements","Collections"),tr("Cloture","Closing")]])
    rows.append([C("s",tr("Clients","Trade receivables"),TXT),
                 C("f","Balance!C11",MON),C("f","Balance!E11",MON),C("f","Balance!F11",MON),C("f","Balance!G11",MON)])
    para(tr("Analyse par anteriorite non detaillee dans cet exemple. Aucune creance n'a ete identifiee comme "
            "douteuse ou depreciee a la cloture de l'exercice.",
            "Ageing analysis not detailed in this example. No receivable has been identified as doubtful or "
            "impaired at year-end."))
    rows.append([])

    # NOTE 5 - Tresorerie
    section(tr("NOTE 5 - DISPONIBILITES (TRESORERIE)","NOTE 5 - CASH AND CASH EQUIVALENTS"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Ouverture","Opening"),
                 tr("Encaissements","Inflows"),tr("Decaissements","Outflows"),tr("Cloture","Closing")]])
    rows.append([C("s",tr("Banques","Banks"),TXT),
                 C("f","Balance!C13",MON),C("f","Balance!E13",MON),C("f","Balance!F13",MON),C("f","Balance!G13",MON)])
    rows.append([])

    # NOTE 6 - Capitaux propres
    section(tr("NOTE 6 - VARIATION DES CAPITAUX PROPRES","NOTE 6 - STATEMENT OF CHANGES IN EQUITY"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Capital","Capital"),tr("Reserves","Reserves"),
                 tr("Report a nouveau","Retained earnings"),tr("Resultat net","Net result"),tr("Total","Total")]])
    r=len(rows)+1
    rows.append([C("s",tr("Solde au 01/01/2025","Balance at 01/01/2025"),TXT),
                 C("f","Balance!D3",MON),C("f","Balance!D4",MON),C("f","Balance!D5",MON),C("n",0,MON),
                 C("f",f"B{r}+C{r}+D{r}+E{r}",BMON)])
    r=len(rows)+1
    rows.append([C("s",tr("Dividendes verses","Dividends paid"),TXT),
                 C("n",0,MON),C("n",0,MON),C("f","-Balance!E5",MON),C("n",0,MON),
                 C("f",f"B{r}+C{r}+D{r}+E{r}",BMON)])
    r=len(rows)+1
    rows.append([C("s",tr("Resultat net de l'exercice","Net result for the year"),TXT),
                 C("n",0,MON),C("n",0,MON),C("n",0,MON),C("f","Resultat!B19",MON),
                 C("f",f"B{r}+C{r}+D{r}+E{r}",BMON)])
    rows.append([C("s",tr("Solde au 31/12/2025","Balance at 31/12/2025"),BTX),
                 C("f","Balance!H3",BMON),C("f","Balance!H4",BMON),C("f","Balance!H5-Balance!G5",BMON),
                 C("f","Resultat!B19",BMON),C("f","Bilan!D21",BMON)])
    rows.append([])

    # NOTE 7 - Emprunts
    section(tr("NOTE 7 - EMPRUNTS ET DETTES FINANCIERES","NOTE 7 - BORROWINGS AND FINANCIAL LIABILITIES"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Ouverture","Opening"),
                 tr("Nouveaux emprunts","New borrowings"),tr("Remboursements","Repayments"),tr("Cloture","Closing")]])
    rows.append([C("s",tr("Emprunts","Borrowings"),TXT),
                 C("f","Balance!D6",MON),C("f","Balance!F6",MON),C("f","Balance!E6",MON),C("f","Balance!H6",MON)])
    para(tr("A titre d'hypothese simplificatrice pour cet exemple, l'integralite de l'encours au 31/12/2025 est "
            "presentee en dettes financieres a plus d'un an ; aucun echeancier detaille n'est disponible.",
            "As a simplifying assumption for this example, the entire balance at 31/12/2025 is presented as "
            "non-current financial liabilities; no detailed repayment schedule is available."))
    rows.append([])

    # NOTE 8 - Fournisseurs
    section(tr("NOTE 8 - FOURNISSEURS ET AUTRES DETTES","NOTE 8 - TRADE PAYABLES AND OTHER LIABILITIES"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Ouverture","Opening"),
                 tr("Mouv. debit","Debit mvts"),tr("Mouv. credit","Credit mvts"),tr("Cloture","Closing")]])
    rows.append([C("s",tr("Fournisseurs","Trade payables"),TXT),
                 C("f","Balance!D10",MON),C("f","Balance!E10",MON),C("f","Balance!F10",MON),C("f","Balance!H10",MON)])
    rows.append([C("s",tr("Etat, impot sur benefices","State, income tax"),TXT),
                 C("f","Balance!D12",MON),C("f","Balance!E12",MON),C("f","Balance!F12",MON),C("f","Balance!H12",MON)])
    rows.append([])

    # NOTE 9 - Provisions
    section(tr("NOTE 9 - PROVISIONS ET ENGAGEMENTS HORS BILAN","NOTE 9 - PROVISIONS AND OFF-BALANCE SHEET COMMITMENTS"))
    para(tr("Aucune provision pour risques et charges n'a ete comptabilisee au titre de l'exercice. Aucun "
            "engagement hors bilan significatif (garanties donnees, cautions, engagements de credit-bail) "
            "n'a ete identifie a la date de cloture.",
            "No provision for contingencies has been recognised for the year. No significant off-balance sheet "
            "commitment (guarantees given, sureties, finance lease commitments) has been identified at year-end."))
    rows.append([])

    # NOTE 10 - Impots
    section(tr("NOTE 10 - IMPOTS SUR LE RESULTAT","NOTE 10 - INCOME TAX"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Montant","Amount")]])
    r_avant=len(rows)+1
    rows.append([C("s",tr("Resultat avant impot","Profit before tax"),TXT),C("f","Resultat!B17",MON)])
    r_impot=len(rows)+1
    rows.append([C("s",tr("Charge d'impot sur le resultat","Income tax expense"),TXT),C("f","Resultat!B18",MON)])
    rows.append([C("s",tr("Taux effectif d'imposition","Effective tax rate"),BTX),
                 C("f",f"-B{r_impot}/B{r_avant}",PCT)])
    rows.append([])

    # NOTE 11 - Personnel
    section(tr("NOTE 11 - CHARGES DE PERSONNEL","NOTE 11 - PERSONNEL EXPENSES"))
    rows.append([C("s",h,HDR) for h in [tr("Rubrique","Item"),tr("Montant","Amount")]])
    rows.append([C("s",tr("Charges de personnel de l'exercice","Personnel expenses for the year"),TXT),
                 C("f","Balance!E17",MON)])
    para(tr("Effectif moyen de l'exercice : non renseigne dans cet exemple simplifie.",
            "Average headcount for the year: not disclosed in this simplified example."))
    rows.append([])

    # NOTE 12 - Evenements posterieurs
    section(tr("NOTE 12 - EVENEMENTS POSTERIEURS A LA CLOTURE","NOTE 12 - EVENTS AFTER THE REPORTING PERIOD"))
    para(tr("Neant a la date d'arrete des presents etats financiers.",
            "None as at the date of approval of these financial statements."))

    merges=["A1:I1"]+[f"A{r}:I{r}" for r in section_rows]+[f"A{r}:I{r}" for r in para_rows]
    row_heights={r:45 for r in para_rows}
    return render_sheet(rows,[42,14,14,14,14,14,14,14,14],freeze_row=0,merges=merges,row_heights=row_heights)

def month_labels():
    return [tr("Janv","Jan"),tr("Fevr","Feb"),tr("Mars","Mar"),tr("Avr","Apr"),tr("Mai","May"),tr("Juin","Jun"),
            tr("Juil","Jul"),tr("Aout","Aug"),tr("Sept","Sep"),tr("Oct","Oct"),tr("Nov","Nov"),tr("Dec","Dec")]

def MC(code,col,m_cell):
    """SUMIFS cumulatif jusqu'au mois m_cell sur Journal (col='F' debit ou 'G' credit)."""
    return f'SUMIFS(Journal!${col}:${col},Journal!$C:$C,{code},Journal!$H:$H,"<="&{m_cell})'

def build_graphiques():
    rows=[]
    rows.append([C("s",tr("GRAPHIQUES - TABLEAU DE BORD 2025","CHARTS - DASHBOARD 2025"),TITLE)])          # 1
    rows.append([])                                                                                          # 2
    rows.append([C("s",tr("EVOLUTION MENSUELLE (cumulee)","MONTHLY EVOLUTION (cumulative)"),HDR)])           # 3
    rows.append([C("s",tr("Mois","Month"),HDR)]+[C("s",m,HDR) for m in month_labels()])                      # 4
    rows.append([C("s",tr("N. mois","Month No."),TXT)]+[C("n",i+1,CODE) for i in range(12)])                 # 5
    ca_row=[C("s",tr("CA cumule (Ventes)","Cumulative Sales"),BTX)]
    res_row=[C("s",tr("Resultat cumule","Cumulative Net Result"),BTX)]
    tre_row=[C("s",tr("Tresorerie (Banques)","Cash (Banks)"),BTX)]
    for i in range(12):
        col=col_letter(1+i); m_cell=f"{col}$5"
        ca_row.append(C("f",MC(701,"G",m_cell),MON))
        res_f=(f'{MC(701,"G",m_cell)}-{MC(601,"F",m_cell)}-({MC(6031,"F",m_cell)}-{MC(6031,"G",m_cell)})'
               f'-{MC(62,"F",m_cell)}-{MC(66,"F",m_cell)}-{MC(681,"F",m_cell)}-{MC(671,"F",m_cell)}-{MC(891,"F",m_cell)}')
        res_row.append(C("f",res_f,MON))
        tre_row.append(C("f",f'{MC(52,"F",m_cell)}-{MC(52,"G",m_cell)}',MON))
    rows.append(ca_row)   # 6
    rows.append(res_row)  # 7
    rows.append(tre_row)  # 8
    rows.append([])       # 9
    rows.append([C("s",tr("STRUCTURE DU BILAN (31/12/2025)","BALANCE SHEET STRUCTURE (31/12/2025)"),HDR)])   # 10
    rows.append([C("s",tr("Poste","Item"),HDR),C("s",tr("Montant","Amount"),HDR)])                            # 11
    for lbl,f in [
        (tr("Actif immobilise","Non-current assets"),"Bilan!D5"),
        (tr("Actif circulant","Current assets"),"Bilan!D9"),
        (tr("Tresorerie-actif","Cash assets"),"Bilan!D12"),
        (tr("Capitaux propres","Equity"),"Bilan!D21"),
        (tr("Dettes financieres","Financial liabilities"),"Bilan!D24"),
        (tr("Passif circulant","Current liabilities"),"Bilan!D28"),
    ]:
        rows.append([C("s",lbl,TXT),C("f",f,MON)])                                                            # 12-17
    rows.append([])                                                                                            # 18
    rows.append([C("s",tr("RATIOS CLES","KEY RATIOS"),HDR)])                                                   # 19
    rows.append([C("s",tr("Ratio","Ratio"),HDR),C("s",tr("Valeur","Value"),HDR)])                              # 20
    for lbl,f in [
        (tr("Autonomie financiere","Financial autonomy"),"Ratios!B5"),
        (tr("Taux de marge commerciale","Gross trading margin rate"),"Ratios!B7"),
        (tr("Taux de valeur ajoutee","Value added rate"),"Ratios!B8"),
        (tr("Marge nette","Net margin"),"Ratios!B9"),
        (tr("Rentabilite des capitaux (ROE)","Return on equity (ROE)"),"Ratios!B10"),
        (tr("Rentabilite de l'actif (ROA)","Return on assets (ROA)"),"Ratios!B11"),
    ]:
        rows.append([C("s",lbl,TXT),C("f",f,PCT)])                                                             # 21-26
    return render_sheet(rows,[30]+[12]*12,freeze_row=0,
                         merges=["A1:M1","A3:M3","A10:B10","A19:B19"],drawing_rid="rId1")

def chart_series_cat_num(cat_ref,cat_labels,val_ref,values,fmt="#,##0"):
    cat_pts="".join(f'<c:pt idx="{i}"><c:v>{escape(str(v))}</c:v></c:pt>' for i,v in enumerate(cat_labels))
    val_pts="".join(f'<c:pt idx="{i}"><c:v>{v}</c:v></c:pt>' for i,v in enumerate(values))
    cat=(f'<c:cat><c:strRef><c:f>{escape(cat_ref)}</c:f><c:strCache><c:ptCount val="{len(cat_labels)}"/>'
         f'{cat_pts}</c:strCache></c:strRef></c:cat>')
    val=(f'<c:val><c:numRef><c:f>{escape(val_ref)}</c:f><c:numCache><c:formatCode>{fmt}</c:formatCode>'
         f'<c:ptCount val="{len(values)}"/>{val_pts}</c:numCache></c:numRef></c:val>')
    return cat,val

def chart_xml(title,kind,cat_ref,cat_labels,series,fmt="#,##0",pct_axis=False):
    """kind: 'line' ou 'bar'. series: liste de (nom, val_ref, valeurs)."""
    sers=[]
    for idx,(name,val_ref,values) in enumerate(series):
        cat,val=chart_series_cat_num(cat_ref,cat_labels,val_ref,values,fmt)
        marker='<c:marker><c:symbol val="circle"/><c:size val="5"/></c:marker>' if kind=="line" else ""
        sers.append(f'<c:ser><c:idx val="{idx}"/><c:order val="{idx}"/>'
                    f'<c:tx><c:v>{escape(name)}</c:v></c:tx>{marker}{cat}{val}</c:ser>')
    ser_xml="".join(sers)
    if kind=="line":
        plot=(f'<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>{ser_xml}'
              '<c:marker val="1"/><c:axId val="111111111"/><c:axId val="222222222"/></c:lineChart>')
    else:
        plot=(f'<c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:varyColors val="0"/>{ser_xml}'
              '<c:gapWidth val="80"/><c:axId val="111111111"/><c:axId val="222222222"/></c:barChart>')
    val_numfmt='<c:numFmt formatCode="0.0%" sourceLinked="0"/>' if pct_axis else ""
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      '<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" '
      'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
      '<c:chart>'
      f'<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>{escape(title)}</a:t></a:r></a:p></c:rich></c:tx>'
      '<c:overlay val="0"/></c:title><c:autoTitleDeleted val="0"/>'
      f'<c:plotArea><c:layout/>{plot}'
      '<c:catAx><c:axId val="111111111"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/>'
      '<c:axPos val="b"/><c:crossAx val="222222222"/></c:catAx>'
      f'<c:valAx><c:axId val="222222222"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/>'
      f'<c:axPos val="l"/>{val_numfmt}<c:crossAx val="111111111"/></c:valAx>'
      '</c:plotArea>'
      '<c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend>'
      '<c:plotVisOnly val="1"/>'
      '</c:chart></c:chartSpace>')

def build_chart_parts():
    ca=[0,300000,300000,300000,300000,300000,300000,300000,300000,300000,300000,300000]
    res=[0,300000,300000,140000,140000,140000,80000,55000,55000,47000,47000,5000]
    tre=[50000,50000,340000,340000,340000,340000,115000,90000,90000,52000,72000,48000]
    months=month_labels()
    chart1=chart_xml(tr("Courbe de performance (CA et Resultat cumules)",
                         "Performance curve (cumulative sales & net result)"),
                      "line","Graphiques!$B$4:$M$4",months,
                      [(tr("CA cumule","Cumulative sales"),"Graphiques!$B$6:$M$6",ca),
                       (tr("Resultat cumule","Cumulative net result"),"Graphiques!$B$7:$M$7",res)])
    chart2=chart_xml(tr("Evolution de la tresorerie","Cash evolution"),
                      "line","Graphiques!$B$4:$M$4",months,
                      [(tr("Tresorerie","Cash"),"Graphiques!$B$8:$M$8",tre)])
    bilan_labels=[tr("Actif immob.","Non-curr. assets"),tr("Actif circ.","Current assets"),
                  tr("Tresorerie","Cash"),tr("Cap. propres","Equity"),
                  tr("Dettes fin.","Fin. liabilities"),tr("Passif circ.","Curr. liabilities")]
    bilan_vals=[150000,70000,48000,145000,100000,23000]
    chart3=chart_xml(tr("Structure du bilan","Balance sheet structure"),
                      "bar","Graphiques!$A$12:$A$17",bilan_labels,
                      [(tr("Montant (FCFA)","Amount (FCFA)"),"Graphiques!$B$12:$B$17",bilan_vals)])
    ratio_labels=[tr("Autonomie fin.","Fin. autonomy"),tr("Marge comm.","Trading margin"),
                  tr("Valeur ajoutee","Value added"),tr("Marge nette","Net margin"),tr("ROE","ROE"),tr("ROA","ROA")]
    ratio_vals=[0.5410,0.4333,0.35,0.0167,0.0345,0.0187]
    chart4=chart_xml(tr("Ratios cles","Key ratios"),
                      "bar","Graphiques!$A$21:$A$26",ratio_labels,
                      [(tr("Valeur","Value"),"Graphiques!$B$21:$B$26",ratio_vals)],fmt="0.0%",pct_axis=True)
    return [chart1,chart2,chart3,chart4]

def drawing_xml():
    anchors=[
      (14,0,22,16,1,tr("Courbe de performance","Performance curve")),
      (22,0,30,16,2,tr("Evolution tresorerie","Cash evolution")),
      (14,17,22,33,3,tr("Structure du bilan","Balance sheet structure")),
      (22,17,30,33,4,tr("Ratios cles","Key ratios")),
    ]
    parts=['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
      '<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" '
      'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">']
    for fc,fr,tc,trow,rid,name in anchors:
        parts.append(
          '<xdr:twoCellAnchor>'
          f'<xdr:from><xdr:col>{fc}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{fr}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>'
          f'<xdr:to><xdr:col>{tc}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>{trow}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>'
          '<xdr:graphicFrame macro="">'
          f'<xdr:nvGraphicFramePr><xdr:cNvPr id="{rid+1}" name="{escape(name)}"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>'
          '<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>'
          '<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">'
          f'<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" '
          f'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:id="rId{rid}"/>'
          '</a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>')
    parts.append('</xdr:wsDr>')
    return "".join(parts)

def drawing_rels():
    rel="".join(f'<Relationship Id="rId{i+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" '
                f'Target="../charts/chart{i+1}.xml"/>' for i in range(4))
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{rel}</Relationships>')

def sheet_drawing_rels():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" '
      'Target="../drawings/drawing1.xml"/></Relationships>')

STYLES='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
 <numFmts count="3">
  <numFmt numFmtId="164" formatCode="#,##0"/><numFmt numFmtId="165" formatCode="0.0%"/><numFmt numFmtId="166" formatCode="0.00"/>
 </numFmts>
 <fonts count="5">
  <font><sz val="11"/><name val="Calibri"/></font>
  <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
  <font><sz val="11"/><color rgb="FF111827"/><name val="Calibri"/></font>
  <font><b/><sz val="11"/><color rgb="FF111827"/><name val="Calibri"/></font>
  <font><b/><sz val="14"/><color rgb="FF15803D"/><name val="Calibri"/></font>
 </fonts>
 <fills count="4">
  <fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
  <fill><patternFill patternType="solid"><fgColor rgb="FF15803D"/></patternFill></fill>
  <fill><patternFill patternType="solid"><fgColor rgb="FFECFDF5"/></patternFill></fill>
 </fills>
 <borders count="2">
  <border><left/><right/><top/><bottom/><diagonal/></border>
  <border><left style="thin"><color rgb="FFE5E7EB"/></left><right style="thin"><color rgb="FFE5E7EB"/></right><top style="thin"><color rgb="FFE5E7EB"/></top><bottom style="thin"><color rgb="FFE5E7EB"/></bottom></border>
 </borders>
 <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
 <cellXfs count="11">
  <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  <xf numFmtId="0" fontId="1" fillId="2" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="center" wrapText="1"/></xf>
  <xf numFmtId="0" fontId="2" fillId="0" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="left"/></xf>
  <xf numFmtId="0" fontId="3" fillId="3" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="left"/></xf>
  <xf numFmtId="164" fontId="2" fillId="0" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf>
  <xf numFmtId="164" fontId="3" fillId="3" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf>
  <xf numFmtId="165" fontId="2" fillId="0" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf>
  <xf numFmtId="166" fontId="2" fillId="0" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="right"/></xf>
  <xf numFmtId="0" fontId="4" fillId="0" borderId="0" applyAlignment="1"><alignment vertical="center"/></xf>
  <xf numFmtId="1" fontId="2" fillId="0" borderId="1" applyAlignment="1"><alignment vertical="center" horizontal="left"/></xf>
  <xf numFmtId="0" fontId="2" fillId="0" borderId="1" applyAlignment="1"><alignment vertical="top" horizontal="left" wrapText="1"/></xf>
 </cellXfs>
</styleSheet>'''

SHEETS=[("Journal",build_journal),("Balance",build_balance),("Resultat",build_resultat),
        ("Bilan",build_bilan),("Tresorerie",build_tresorerie),("Notes",build_notes),
        ("Ratios",build_ratios),("Graphiques",build_graphiques)]

def content_types():
    ov="".join(f'<Override PartName="/xl/worksheets/sheet{i+1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in range(len(SHEETS)))
    charts="".join(f'<Override PartName="/xl/charts/chart{i+1}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>' for i in range(4))
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
     '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
     '<Default Extension="xml" ContentType="application/xml"/>'
     '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
     '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
     '<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>'
     f'{ov}{charts}</Types>')
def workbook_xml():
    sh="".join(f'<sheet name="{n}" sheetId="{i+1}" r:id="rId{i+1}"/>' for i,(n,_) in enumerate(SHEETS))
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
     f'<sheets>{sh}</sheets><calcPr calcId="0" fullCalcOnLoad="1"/></workbook>')
def workbook_rels():
    rl="".join(f'<Relationship Id="rId{i+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i+1}.xml"/>' for i in range(len(SHEETS)))
    sid=len(SHEETS)+1
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
     f'{rl}<Relationship Id="rId{sid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
ROOT_RELS=('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
 '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')

def write_file(path):
    with zipfile.ZipFile(path,"w",zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",content_types())
        z.writestr("_rels/.rels",ROOT_RELS)
        z.writestr("xl/workbook.xml",workbook_xml())
        z.writestr("xl/_rels/workbook.xml.rels",workbook_rels())
        z.writestr("xl/styles.xml",STYLES)
        for i,(n,fn) in enumerate(SHEETS):
            z.writestr(f"xl/worksheets/sheet{i+1}.xml",fn())
            if n=="Graphiques":
                z.writestr(f"xl/worksheets/_rels/sheet{i+1}.xml.rels",sheet_drawing_rels())
        z.writestr("xl/drawings/drawing1.xml",drawing_xml())
        z.writestr("xl/drawings/_rels/drawing1.xml.rels",drawing_rels())
        for j,cxml in enumerate(build_chart_parts()):
            z.writestr(f"xl/charts/chart{j+1}.xml",cxml)
    print("OK ->",path)

if __name__=="__main__":
    LANG="fr"; write_file("/home/pascal-tshingila/M-FINAPP/Etats_Financiers_OHADA_Exemple.xlsx")
    LANG="en"; write_file("/home/pascal-tshingila/M-FINAPP/Financial_Statements_OHADA_Example_EN.xlsx")

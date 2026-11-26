-- Banques et operateurs mobile money des comptes de tresorerie deja mouvementes.
--
-- Un journal importe pouvait mouvementer un compte de banque (ex. 5215 « Banque — Equity Bank ») sans que la banque
-- existe dans l'application : l'ecran Banques & Mobile Money restait vide et un ajout manuel ouvrait un second compte
-- a solde nul. L'import cree desormais l'etablissement lui-meme ; cette migration rattrape les comptes deja dans ce
-- cas. Aucun montant n'est saisi : le solde d'un etablissement se lit dans le grand livre, il est donc d'emblee celui
-- des ecritures passees. Memes regles que EtablissementTresorerieService.typePourCompte et nomDepuisLibelle.
WITH orphelins AS (
    SELECT c.id,
           c.numero,
           CASE WHEN c.numero LIKE '552%' OR (c.numero LIKE '55%' AND c.libelle LIKE 'Mobile Money — %')
                THEN 'MOBILE_MONEY' ELSE 'BANQUE' END AS type,
           left(coalesce(nullif(btrim(regexp_replace(coalesce(c.libelle, ''),
                '^(banque|mobile money)\s*[—–-]\s*', '', 'i')), ''), 'Compte ' || c.numero), 100) AS nom
    FROM comptes_ohada c
    WHERE c.classe = 5
      AND c.actif
      AND c.imputable
      AND (c.numero LIKE '521%' OR (c.numero LIKE '52%' AND c.libelle LIKE 'Banque — %')
           OR c.numero LIKE '552%' OR (c.numero LIKE '55%' AND c.libelle LIKE 'Mobile Money — %'))
      AND NOT EXISTS (SELECT 1 FROM etablissements_tresorerie e WHERE e.compte_id = c.id)
      AND EXISTS (SELECT 1 FROM grand_livre g WHERE g.compte_id = c.id)
),
nommes AS (
    SELECT o.*, row_number() OVER (PARTITION BY lower(o.nom), o.type ORDER BY o.numero) AS rang
    FROM orphelins o
)
INSERT INTO etablissements_tresorerie (nom, type, compte_id, actif, devise)
SELECT CASE
           WHEN n.rang > 1 OR EXISTS (SELECT 1 FROM etablissements_tresorerie e
                                      WHERE lower(e.nom) = lower(n.nom) AND e.type = n.type)
           THEN left(n.nom, 100 - length(n.numero) - 3) || ' (' || n.numero || ')'
           ELSE n.nom
       END,
       n.type, n.id, TRUE, 'USD'
FROM nommes n;

-- ---------------------------------------------------------------------------
-- PaieComptabilisationService credite la CNSS (part ouvriere + part
-- patronale reunies) sur un compte dedie plutot que sur "431 Securite
-- sociale", qui est un compte de regroupement non imputable (comme "706" ou
-- "441") -- meme logique que 4478.1/4478.2 ajoutes en V43 pour ONEM/INPP.
-- ---------------------------------------------------------------------------
INSERT INTO comptes_ohada (numero, libelle, type, classe, imputable, actif, manuel) VALUES
    ('431.1', 'CNSS à payer', 'PASSIF', 4, TRUE, TRUE, TRUE)
ON CONFLICT (numero) DO NOTHING;

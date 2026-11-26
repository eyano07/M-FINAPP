-- Historique du circuit des notes de frais : le texte d'une étape ne répète plus le rôle de son
-- auteur (« Validee par le DA » devient « Validee »), la fonction de l'auteur s'affichant désormais
-- devant son nom. Les étapes déjà enregistrées reçoivent la même formulation que les nouvelles ;
-- le statut de la note à ce moment-là, l'auteur et la date ne changent pas.
-- Seuls les textes écrits par le circuit sont touchés (motifs anciens ou nouveaux inclus, après « : »).
UPDATE observations_note
   SET commentaire = regexp_replace(commentaire, '^(Verifiee|Validee|Rejetee) par le (DFIN|DA)( : |$)', '\1\3')
 WHERE commentaire ~ '^(Verifiee|Validee|Rejetee) par le (DFIN|DA)( : |$)';

UPDATE observations_note
   SET commentaire = regexp_replace(commentaire, '^Comptes d''imputation modifies par le DFIN : ', 'Comptes d''imputation modifies : ')
 WHERE commentaire LIKE 'Comptes d''imputation modifies par le DFIN : %';

UPDATE observations_note
   SET commentaire = regexp_replace(commentaire, '^Libellé modifié par le DFIN : ', 'Libellé modifié : ')
 WHERE commentaire LIKE 'Libellé modifié par le DFIN : %';

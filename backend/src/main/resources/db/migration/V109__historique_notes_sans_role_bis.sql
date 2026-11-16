-- ---------------------------------------------------------------------------
-- Etapes de l'historique des notes ecrites avec le libelle d'un role (« Validee par DA », « Verifiee par DFIN »,
-- « Soumission a DFIN », « Validee hors budget par DAF »...) : meme normalisation que V99. Le texte de l'etape ne
-- cite plus de role ; la fonction de l'auteur s'affiche devant son nom. Le commentaire saisi (« : ... ») est garde.
-- ---------------------------------------------------------------------------
UPDATE observations_note
   SET commentaire = regexp_replace(commentaire, '^(Verifiee|Validee hors budget|Validee|Rejetee) par [^:]+?( : |$)', '\1\2')
 WHERE commentaire ~ '^(Verifiee|Validee hors budget|Validee|Rejetee) par [^:]+?( : |$)';

UPDATE observations_note
   SET commentaire = regexp_replace(commentaire, '^Soumission (a|au) [^:]+?( : |$)', 'Soumission\2')
 WHERE commentaire ~ '^Soumission (a|au) [^:]+?( : |$)';

UPDATE observations_note
   SET commentaire = regexp_replace(commentaire, '^Comptes d''imputation modifies par [^:]+ : ', 'Comptes d''imputation modifies : ')
 WHERE commentaire ~ '^Comptes d''imputation modifies par [^:]+ : ';

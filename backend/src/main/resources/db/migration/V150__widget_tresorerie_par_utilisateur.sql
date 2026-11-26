-- =====================================================================
-- V150 : widget « Trésorerie » activé par chaque utilisateur (18/11/2026)
-- =====================================================================
-- Le widget Trésorerie (mini tableau de bord de l'écran d'accueil Android) ne s'active plus pour toute l'entreprise par
-- l'administrateur (V149) : chaque compte caissier, DA, DFIN, comptable ou DG l'active pour lui-même dans « Mon profil »
-- (préférence « widget.tresorerie » de preferences_utilisateur).
ALTER TABLE parametres_entreprise DROP COLUMN IF EXISTS widget_tresorerie;

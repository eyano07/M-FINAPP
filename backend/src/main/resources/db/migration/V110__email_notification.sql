-- Adresse e-mail (optionnelle) a laquelle l'utilisateur recoit ses notifications.
ALTER TABLE users ADD COLUMN email_notification VARCHAR(150);

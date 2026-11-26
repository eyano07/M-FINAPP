-- Un agent d'un ordre de mission peut desormais etre une personne externe
-- (partenaire, entrepreneur etranger...) sans compte employe dans le
-- systeme : employe_id devient facultatif, complete alors par un nom libre.
-- Nationalite et numero de passeport s'appliquent aux deux cas (employe ou
-- personne externe) — le document officiel les mentionne pour chaque agent.
ALTER TABLE drh_agents_ordre_mission ALTER COLUMN employe_id DROP NOT NULL;
ALTER TABLE drh_agents_ordre_mission ADD COLUMN nom_libre VARCHAR(150);
ALTER TABLE drh_agents_ordre_mission ADD COLUMN nationalite VARCHAR(60);
ALTER TABLE drh_agents_ordre_mission ADD COLUMN numero_passeport VARCHAR(60);
ALTER TABLE drh_agents_ordre_mission ADD CONSTRAINT chk_agent_om_identite
  CHECK (employe_id IS NOT NULL OR nom_libre IS NOT NULL);

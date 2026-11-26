ALTER TABLE ventes
    ADD COLUMN table_id BIGINT REFERENCES tables_restaurant(id);

CREATE INDEX idx_ventes_table ON ventes(table_id);

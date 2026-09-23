-- Corrections change product facts, inventory and audit sources only.
-- Immutable sales/history rows remain owned by the original imported dataset.
ALTER TABLE datasets ADD COLUMN history_dataset_id text REFERENCES datasets(dataset_id);
ALTER TABLE datasets ADD CONSTRAINT history_owner_not_self CHECK (history_dataset_id IS NULL OR history_dataset_id <> dataset_id);

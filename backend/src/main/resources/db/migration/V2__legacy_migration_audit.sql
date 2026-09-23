CREATE TABLE migration_sources (
    entity_id text PRIMARY KEY,
    entity_kind text NOT NULL CHECK (entity_kind IN ('dataset','calculation')),
    source_sha256 text NOT NULL,
    reserialized_dataset_id text,
    migrated_at timestamptz NOT NULL DEFAULT now()
);

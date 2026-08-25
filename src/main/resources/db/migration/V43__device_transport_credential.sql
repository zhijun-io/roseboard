-- Evolve device_credentials toward ThingsBoard shape + roseboard security fields.
-- TB: credentials_id (lookup), credentials_value (type-specific JSON/PEM), one row per device.
ALTER TABLE device_credentials
    ADD COLUMN IF NOT EXISTS credentials_value text,
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS security_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS secret_key_version integer;

ALTER TABLE device_credentials
    ALTER COLUMN credentials_value_hash DROP NOT NULL;

ALTER TABLE device_credentials
    DROP CONSTRAINT IF EXISTS device_credentials_type_hash_unq_key;

CREATE UNIQUE INDEX IF NOT EXISTS device_credentials_id_unq_key
    ON device_credentials (credentials_id)
    WHERE credentials_id IS NOT NULL;

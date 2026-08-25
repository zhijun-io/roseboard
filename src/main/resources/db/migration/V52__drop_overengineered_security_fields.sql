-- Drop withdrawn governance leftovers and credentials fields not present in ThingsBoard.

DROP TABLE IF EXISTS governance_task_lease;

ALTER TABLE tb_user
    DROP COLUMN IF EXISTS security_version;

ALTER TABLE device
    DROP COLUMN IF EXISTS security_version;

DROP INDEX IF EXISTS device_credentials_hash_idx;

ALTER TABLE device_credentials
    DROP COLUMN IF EXISTS security_version,
    DROP COLUMN IF EXISTS credentials_value_hash,
    DROP COLUMN IF EXISTS secret_key_version;

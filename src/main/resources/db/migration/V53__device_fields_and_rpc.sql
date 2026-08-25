-- Device TB field parity + Persistent RPC (TB Rpc shape)

ALTER TABLE device
    ADD COLUMN IF NOT EXISTS device_data jsonb,
    ADD COLUMN IF NOT EXISTS external_id uuid;

CREATE UNIQUE INDEX IF NOT EXISTS device_tenant_external_id_uq
    ON device (tenant_id, external_id)
    WHERE external_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS device_rpc (
    id                 uuid PRIMARY KEY,
    created_time       bigint       NOT NULL,
    tenant_id          uuid         NOT NULL REFERENCES tenant(id),
    device_id          uuid         NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    expiration_time    bigint       NOT NULL,
    request            jsonb        NOT NULL,
    response           jsonb,
    status             varchar(32)  NOT NULL,
    additional_info    jsonb
);

CREATE INDEX IF NOT EXISTS device_rpc_device_status_idx
    ON device_rpc (tenant_id, device_id, status);
CREATE INDEX IF NOT EXISTS device_rpc_expiration_idx
    ON device_rpc (status, expiration_time);

CREATE TABLE device_rpc (
    id                 uuid PRIMARY KEY,
    created_time       bigint       NOT NULL,
    tenant_id          uuid         NOT NULL REFERENCES tenant(id),
    device_id          uuid         NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    direction          varchar(32)  NOT NULL,
    method             varchar(128) NOT NULL,
    params_json        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status             varchar(32)  NOT NULL,
    idempotency_key    varchar(128),
    deadline_at        bigint       NOT NULL,
    response_json      jsonb,
    completed_time     bigint,
    CONSTRAINT device_rpc_idempotency_uq UNIQUE (tenant_id, device_id, idempotency_key)
);

CREATE INDEX device_rpc_device_status_idx ON device_rpc (tenant_id, device_id, status);
CREATE INDEX device_rpc_deadline_idx ON device_rpc (status, deadline_at);

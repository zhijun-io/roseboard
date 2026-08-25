CREATE TABLE device_transport_config (
    id                 uuid PRIMARY KEY,
    created_time       bigint       NOT NULL,
    tenant_id          uuid         NOT NULL REFERENCES tenant(id),
    device_profile_id  uuid         NOT NULL REFERENCES device_profile(id) ON DELETE CASCADE,
    transport_type     varchar(32)  NOT NULL,
    payload_format     varchar(32)  NOT NULL,
    config_json        jsonb        NOT NULL,
    version            bigint       NOT NULL DEFAULT 1,
    CONSTRAINT device_transport_config_profile_type_uq UNIQUE (device_profile_id, transport_type)
);

CREATE INDEX device_transport_config_tenant_idx ON device_transport_config (tenant_id);
CREATE INDEX device_transport_config_profile_idx ON device_transport_config (device_profile_id);

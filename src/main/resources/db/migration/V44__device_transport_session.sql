CREATE TABLE device_transport_session (
    id                      uuid PRIMARY KEY,
    created_time            bigint       NOT NULL,
    tenant_id               uuid         NOT NULL REFERENCES tenant(id),
    device_id               uuid         NOT NULL REFERENCES device(id) ON DELETE CASCADE,
    protocol                varchar(32)  NOT NULL,
    owner_id                varchar(128) NOT NULL,
    generation              bigint       NOT NULL,
    lease_until             bigint       NOT NULL,
    last_activity_time      bigint       NOT NULL,
    state                   varchar(32)  NOT NULL,
    credential_security_version bigint,
    config_security_version bigint,
    CONSTRAINT device_transport_session_device_uq UNIQUE (device_id)
);

CREATE INDEX device_transport_session_lease_idx
    ON device_transport_session (state, lease_until);

CREATE TABLE device_lwm2m_resource_mapping (
    id                  uuid PRIMARY KEY,
    created_time        bigint       NOT NULL,
    tenant_id           uuid         NOT NULL REFERENCES tenant(id),
    device_profile_id   uuid         NOT NULL REFERENCES device_profile(id) ON DELETE CASCADE,
    transport_version   bigint       NOT NULL,
    object_id           integer      NOT NULL,
    object_instance_id  integer      NOT NULL,
    resource_id         integer      NOT NULL,
    resource_type       varchar(32)  NOT NULL,
    attribute_key       varchar(128),
    telemetry_key       varchar(128),
    CONSTRAINT device_lwm2m_resource_mapping_uq
        UNIQUE (device_profile_id, transport_version, object_id, object_instance_id, resource_id)
);

CREATE TABLE device_snmp_oid_mapping (
    id                  uuid PRIMARY KEY,
    created_time        bigint       NOT NULL,
    tenant_id           uuid         NOT NULL REFERENCES tenant(id),
    device_profile_id   uuid         NOT NULL REFERENCES device_profile(id) ON DELETE CASCADE,
    transport_version   bigint       NOT NULL,
    oid                 varchar(256) NOT NULL,
    value_type          varchar(32)  NOT NULL,
    attribute_key       varchar(128),
    telemetry_key       varchar(128),
    CONSTRAINT device_snmp_oid_mapping_uq
        UNIQUE (device_profile_id, transport_version, oid)
);

ALTER TABLE device_profile ADD COLUMN IF NOT EXISTS profile_data jsonb NOT NULL DEFAULT '{}'::jsonb;

DROP TABLE IF EXISTS device_snmp_oid_mapping;
DROP TABLE IF EXISTS device_lwm2m_resource_mapping;
DROP TABLE IF EXISTS device_transport_config;

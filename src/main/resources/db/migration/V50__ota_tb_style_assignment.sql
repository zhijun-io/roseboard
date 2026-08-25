-- Align OTA assignment with ThingsBoard: columns on device/device_profile; drop assignment/deployment tables.
ALTER TABLE device
    ADD COLUMN IF NOT EXISTS firmware_id uuid REFERENCES ota_package(id),
    ADD COLUMN IF NOT EXISTS software_id uuid REFERENCES ota_package(id);

ALTER TABLE device_profile
    ADD COLUMN IF NOT EXISTS firmware_id uuid REFERENCES ota_package(id),
    ADD COLUMN IF NOT EXISTS software_id uuid REFERENCES ota_package(id);

CREATE INDEX IF NOT EXISTS device_firmware_id_idx ON device (firmware_id) WHERE firmware_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS device_software_id_idx ON device (software_id) WHERE software_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS device_profile_firmware_id_idx ON device_profile (firmware_id) WHERE firmware_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS device_profile_software_id_idx ON device_profile (software_id) WHERE software_id IS NOT NULL;

DROP TABLE IF EXISTS ota_deployment;
DROP TABLE IF EXISTS ota_assignment;

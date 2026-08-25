ALTER TABLE tb_user
    ADD COLUMN IF NOT EXISTS security_version bigint NOT NULL DEFAULT 1;

ALTER TABLE device
    ADD COLUMN IF NOT EXISTS security_version bigint NOT NULL DEFAULT 1;

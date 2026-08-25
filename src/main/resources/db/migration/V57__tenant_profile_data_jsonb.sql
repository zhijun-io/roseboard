-- Align tenant_profile.profile_data with TB TenantProfileData shape (jsonb).
ALTER TABLE tenant_profile
    ALTER COLUMN profile_data DROP DEFAULT;

ALTER TABLE tenant_profile
    ALTER COLUMN profile_data TYPE jsonb
    USING (
        CASE
            WHEN profile_data IS NULL OR btrim(profile_data::text) = '' THEN
                '{"configuration":{},"queueConfiguration":[]}'::jsonb
            WHEN btrim(profile_data::text) = '{}' THEN
                '{"configuration":{},"queueConfiguration":[]}'::jsonb
            WHEN left(btrim(profile_data::text), 1) = '{' THEN
                btrim(profile_data::text)::jsonb
            ELSE
                '{"configuration":{},"queueConfiguration":[]}'::jsonb
        END
    );

ALTER TABLE tenant_profile
    ALTER COLUMN profile_data SET DEFAULT '{"configuration":{},"queueConfiguration":[]}'::jsonb;

ALTER TABLE tenant_profile
    ALTER COLUMN profile_data SET NOT NULL;

UPDATE tenant_profile
SET profile_data = '{"configuration":{},"queueConfiguration":[]}'::jsonb
WHERE profile_data = '{}'::jsonb
   OR NOT (profile_data ? 'configuration')
   OR NOT (profile_data ? 'queueConfiguration');

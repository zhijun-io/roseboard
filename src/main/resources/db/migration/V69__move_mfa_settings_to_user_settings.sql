insert into user_settings (user_id, type, settings)
select user_id, 'mfa', two_fa_settings::jsonb
from user_auth_settings
where two_fa_settings is not null
on conflict (user_id, type) do update set settings = excluded.settings;

drop table if exists user_auth_settings;

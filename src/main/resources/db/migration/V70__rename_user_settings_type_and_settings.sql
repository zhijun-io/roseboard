alter table user_settings rename column type to key;
alter table user_settings rename column settings to value;

alter table user_settings drop constraint if exists user_settings_pkey;
alter table user_settings add constraint user_settings_pkey primary key (user_id, key);

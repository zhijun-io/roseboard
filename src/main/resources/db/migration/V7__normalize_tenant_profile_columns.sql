do $$
begin
    execute 'alter table tenant_profile alter column name type varchar(255) using name::text';
    execute 'alter table tenant_profile alter column profile_data type varchar using profile_data::text';
end $$;

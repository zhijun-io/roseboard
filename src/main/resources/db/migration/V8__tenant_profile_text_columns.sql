do $$
begin
    if exists (
        select 1 from information_schema.columns
        where table_schema = current_schema() and table_name = 'tenant_profile'
          and column_name = 'name' and data_type <> 'character varying'
    ) then
        alter table tenant_profile alter column name type varchar(255) using name::text;
    end if;
    if exists (
        select 1 from information_schema.columns
        where table_schema = current_schema() and table_name = 'tenant_profile'
          and column_name = 'profile_data' and data_type <> 'character varying'
    ) then
        alter table tenant_profile alter column profile_data type varchar using profile_data::text;
    end if;
 
end $$;

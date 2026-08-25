
create table if not exists domain (
    id uuid not null constraint domain_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null,
    name varchar(255) not null constraint domain_name_unq unique,
    description varchar,
    additional_info varchar,
    oauth2_client_ids varchar default '[]'
);

create table if not exists mail_config_template (
    id uuid not null constraint mail_config_template_pkey primary key,
    created_time bigint not null,
    name varchar(255) not null constraint mail_config_template_name_unq unique,
    config varchar not null
);

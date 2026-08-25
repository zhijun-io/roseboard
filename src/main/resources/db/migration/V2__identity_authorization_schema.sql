create table if not exists admin_settings (
    id uuid not null constraint admin_settings_pkey primary key,
    tenant_id uuid not null,
    created_time bigint not null,
    json_value varchar,
    key varchar(255)
);

create table if not exists tb_user (
    id uuid not null constraint tb_user_pkey primary key,
    created_time bigint not null,
    additional_info varchar,
    authority varchar(255),
    customer_id uuid,
    email varchar(255) unique,
    first_name varchar(255),
    last_name varchar(255),
    phone varchar(255),
    tenant_id uuid,
    version bigint default 1
);

create table if not exists tenant_profile (
    id uuid not null constraint tenant_profile_pkey primary key,
    created_time bigint not null,
    name varchar(255),
    profile_data varchar,
    description varchar,
    is_default boolean,
    isolated_tb_core boolean,
    isolated_tb_rule_engine boolean,
    constraint tenant_profile_name_unq_key unique (name)
);

create table if not exists tenant (
    id uuid not null constraint tenant_pkey primary key,
    created_time bigint not null,
    additional_info varchar,
    tenant_profile_id uuid not null,
    address varchar,
    address2 varchar,
    city varchar(255),
    country varchar(255),
    email varchar(255),
    phone varchar(255),
    region varchar(255),
    state varchar(255),
    title varchar(255),
    zip varchar(255),
    version bigint default 1,
    constraint fk_tenant_profile foreign key (tenant_profile_id) references tenant_profile(id)
);

create table if not exists customer (
    id uuid not null constraint customer_pkey primary key,
    created_time bigint not null,
    additional_info varchar,
    address varchar,
    address2 varchar,
    city varchar(255),
    country varchar(255),
    email varchar(255),
    phone varchar(255),
    state varchar(255),
    tenant_id uuid,
    title varchar(255),
    zip varchar(255),
    external_id uuid,
    is_public boolean,
    version bigint default 1,
    constraint customer_title_unq_key unique (tenant_id, title),
    constraint customer_external_id_unq_key unique (tenant_id, external_id)
);

create table if not exists user_credentials (
    id uuid not null constraint user_credentials_pkey primary key,
    created_time bigint not null,
    activate_token varchar(255) unique,
    activate_token_exp_time bigint,
    enabled boolean,
    password varchar(255),
    reset_token varchar(255) unique,
    reset_token_exp_time bigint,
    user_id uuid unique,
    additional_info varchar default '{}',
    last_login_ts bigint,
    failed_login_attempts int
);

create table if not exists user_auth_settings (
    id uuid not null constraint user_auth_settings_pkey primary key,
    created_time bigint not null,
    user_id uuid unique not null constraint fk_user_auth_settings_user_id references tb_user(id),
    two_fa_settings varchar
);

create table if not exists user_settings (
    user_id uuid not null,
    type varchar(50) not null,
    settings jsonb,
    constraint fk_user_id foreign key (user_id) references tb_user(id) on delete cascade,
    constraint user_settings_pkey primary key (user_id, type)
);

create table if not exists oauth2_client (
    id uuid not null constraint oauth2_client_pkey primary key,
    created_time bigint not null,
    tenant_id uuid not null,
    title varchar(100) not null,
    additional_info varchar,
    client_id varchar(255),
    client_secret varchar(2048),
    authorization_uri varchar(255),
    token_uri varchar(255),
    scope varchar(255),
    platforms varchar(255),
    user_info_uri varchar(255),
    user_name_attribute_name varchar(255),
    jwk_set_uri varchar(255),
    client_authentication_method varchar(255),
    login_button_label varchar(255),
    login_button_icon varchar(255),
    allow_user_creation boolean,
    activate_user boolean,
    type varchar(31),
    basic_email_attribute_key varchar(31),
    basic_first_name_attribute_key varchar(31),
    basic_last_name_attribute_key varchar(31),
    basic_tenant_name_strategy varchar(31),
    basic_tenant_name_pattern varchar(255),
    basic_customer_name_pattern varchar(255),
    basic_default_dashboard_name varchar(255),
    basic_always_full_screen boolean,
    custom_url varchar(255),
    custom_username varchar(255),
    custom_password varchar(255),
    custom_send_token boolean
);

create table if not exists oauth2_client_registration_template (
    id uuid not null constraint oauth2_client_registration_template_pkey primary key,
    created_time bigint not null,
    additional_info varchar,
    provider_id varchar(255),
    authorization_uri varchar(255),
    token_uri varchar(255),
    scope varchar(255),
    user_info_uri varchar(255),
    user_name_attribute_name varchar(255),
    jwk_set_uri varchar(255),
    client_authentication_method varchar(255),
    type varchar(31),
    basic_email_attribute_key varchar(31),
    basic_first_name_attribute_key varchar(31),
    basic_last_name_attribute_key varchar(31),
    basic_tenant_name_strategy varchar(31),
    basic_tenant_name_pattern varchar(255),
    basic_customer_name_pattern varchar(255),
    basic_default_dashboard_name varchar(255),
    basic_always_full_screen boolean,
    comment varchar,
    login_button_icon varchar(255),
    login_button_label varchar(255),
    help_link varchar(255),
    constraint oauth2_template_provider_id_unq_key unique (provider_id)
);

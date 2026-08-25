create table if not exists api_key (
    id uuid not null constraint api_key_pkey primary key,
    created_time bigint not null,
    user_id uuid not null constraint fk_api_key_user references tb_user(id) on delete cascade,
    key_hash varchar(64) not null constraint api_key_hash_unq unique,
    enabled boolean not null default true
);
create index if not exists api_key_user_idx on api_key(user_id);

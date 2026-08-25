create table if not exists notification_record (
    id uuid not null constraint notification_record_pkey primary key,
    created_time bigint not null,
    tenant_id uuid,
    user_id uuid not null constraint fk_notification_user references tb_user(id) on delete cascade,
    kind varchar(50) not null,
    payload varchar,
    read_time bigint
);
create index if not exists notification_record_user_idx on notification_record(user_id, created_time desc);
create index if not exists notification_record_kind_idx on notification_record(kind, created_time desc);

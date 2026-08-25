alter table notification
    add column if not exists delivery_method varchar(32) not null default 'WEB';

create index if not exists notification_recipient_method_status_idx
    on notification (recipient_id, delivery_method, status);

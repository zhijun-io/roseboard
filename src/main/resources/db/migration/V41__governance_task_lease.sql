CREATE TABLE IF NOT EXISTS governance_task_lease (
    task_name     varchar(128) PRIMARY KEY,
    owner_id      varchar(128) NOT NULL,
    generation    bigint       NOT NULL,
    lease_until   bigint       NOT NULL,
    updated_at    bigint       NOT NULL
);

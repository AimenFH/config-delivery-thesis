create table probe_request (
    id             bigint generated always as identity primary key,
    received_at    timestamptz not null default now(),
    pod            text        not null,
    config_version text        not null
);

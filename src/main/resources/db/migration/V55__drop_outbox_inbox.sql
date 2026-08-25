-- Drop Roseboard-invented inbox/outbox (not in ThingsBoard). Session remains.
DROP TABLE IF EXISTS outbox_event;
DROP TABLE IF EXISTS transport_inbox;
DROP TABLE IF EXISTS command_inbox;

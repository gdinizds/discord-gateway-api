CREATE SCHEMA IF NOT EXISTS partman;
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;

SELECT partman.create_parent(
    p_parent_table => 'gateway.message_log',
    p_control      => 'recorded_at',
    p_interval     => '1 day',
    p_premake      => 3
);

UPDATE partman.part_config
SET retention                = '8 days',
    retention_keep_table     = FALSE,
    retention_keep_index     = FALSE,
    infinite_time_partitions = TRUE
WHERE parent_table = 'gateway.message_log';

CREATE EXTENSION IF NOT EXISTS pg_cron;

SELECT cron.schedule(
    'partman-maintenance',
    '0 * * * *',
    $$SELECT partman.run_maintenance(p_analyze := FALSE)$$
);

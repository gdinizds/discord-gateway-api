-- pg_cron: gerenciamento automático de partições do message_log
-- Requer pg_cron instalado no servidor PostgreSQL.
-- Os jobs rodam no banco onde esta migration é aplicada.

CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Cria as partições de hoje e amanhã imediatamente (primeiro deploy e re-runs)
DO $$
DECLARE
    d        DATE;
    suffix   TEXT;
    parent   TEXT;
    i        INT;
BEGIN
    FOR d IN SELECT CURRENT_DATE, CURRENT_DATE + 1 LOOP
        suffix := TO_CHAR(d, 'YYYY_MM_DD');
        parent := 'gateway.message_log_' || suffix;

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %s
                 PARTITION OF gateway.message_log
                 FOR VALUES FROM (%L) TO (%L)
                 PARTITION BY HASH (guild_id)',
            parent, d::TEXT, (d + 1)::TEXT
        );

        FOR i IN 0..7 LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %s_h%s
                     PARTITION OF %s
                     FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
                parent, i, parent, i
            );
        END LOOP;
    END LOOP;
END;
$$;

SELECT cron.schedule(
    'message-log-create-partition',
    '50 23 * * *',
    $$
    DO $inner$
    DECLARE
        d      DATE := CURRENT_DATE + 1;
        suffix TEXT := TO_CHAR(d, 'YYYY_MM_DD');
        parent TEXT := 'gateway.message_log_' || suffix;
        i      INT;
    BEGIN
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %s
                 PARTITION OF gateway.message_log
                 FOR VALUES FROM (%L) TO (%L)
                 PARTITION BY HASH (guild_id)',
            parent, d::TEXT, (d + 1)::TEXT
        );
        FOR i IN 0..7 LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %s_h%s
                     PARTITION OF %s
                     FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
                parent, i, parent, i
            );
        END LOOP;
    END;
    $inner$
    $$
);

SELECT cron.schedule(
    'message-log-drop-old-partition',
    '55 23 * * *',
    $$
    DO $inner$
    DECLARE
        old_date   DATE := CURRENT_DATE - 8;
        partition  TEXT := 'gateway.message_log_' || TO_CHAR(old_date, 'YYYY_MM_DD');
    BEGIN
        EXECUTE format('DROP TABLE IF EXISTS %s CASCADE', partition);
    END;
    $inner$
    $$
);

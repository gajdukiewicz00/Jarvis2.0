-- Rename reserved keyword column "interval" to recurrence_interval

DO $$
BEGIN
    -- Legacy schema: only "interval" exists.
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'recurring_transaction'
          AND column_name = 'interval'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'recurring_transaction'
          AND column_name = 'recurrence_interval'
    ) THEN
        EXECUTE 'ALTER TABLE recurring_transaction RENAME COLUMN "interval" TO recurrence_interval';
    END IF;

    -- Partial schema: both columns exist.
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'recurring_transaction'
          AND column_name = 'interval'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'recurring_transaction'
          AND column_name = 'recurrence_interval'
    ) THEN
        EXECUTE 'UPDATE recurring_transaction
                 SET recurrence_interval = COALESCE(recurrence_interval, "interval")';
        EXECUTE 'ALTER TABLE recurring_transaction DROP COLUMN "interval"';
    END IF;
END $$;

ALTER TABLE recurring_transaction
    ALTER COLUMN recurrence_interval SET DEFAULT 'MONTHLY';

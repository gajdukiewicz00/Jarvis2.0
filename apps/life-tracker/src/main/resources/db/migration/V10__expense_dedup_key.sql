-- Dedup key for bank-notification-derived transactions so re-imported drafts don't double-count.
-- Standard SQL UNIQUE constraints treat NULL as distinct from every other value, so manually
-- entered expenses (dedup_key IS NULL) never collide with one another.

ALTER TABLE expense
    ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(64);

ALTER TABLE expense
    ADD CONSTRAINT uq_expense_user_dedup UNIQUE (user_id, dedup_key);

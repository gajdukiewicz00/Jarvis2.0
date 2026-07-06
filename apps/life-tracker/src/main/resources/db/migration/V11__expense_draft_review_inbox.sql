-- FINANCE-REVIEW: manual review inbox for MEDIUM/LOW-confidence (or invalid) bank-notification
-- parses, so they are persisted (status=DRAFT) instead of being dropped (US-BANK-005).

CREATE TABLE IF NOT EXISTS expense_draft (
    id             BIGSERIAL PRIMARY KEY,
    user_id        VARCHAR(255) NOT NULL,
    amount         NUMERIC(19, 2),
    currency       VARCHAR(10),
    category       VARCHAR(100),
    merchant       VARCHAR(255),
    type           VARCHAR(20) DEFAULT 'EXPENSE',
    payment_method VARCHAR(100),
    date           TIMESTAMP,
    confidence     VARCHAR(20),
    dedup_key      VARCHAR(64),
    raw_masked     VARCHAR(1000),
    notes          VARCHAR(1000),
    status         VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_expense_draft_user_status ON expense_draft (user_id, status, created_at DESC);

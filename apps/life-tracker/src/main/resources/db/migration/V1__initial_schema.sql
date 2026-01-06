-- Life Tracker Initial Schema
-- Creates all tables for expenses, time tracking, and calendar events

-- Expense table
CREATE TABLE expense (
    id BIGSERIAL PRIMARY KEY,
    amount DOUBLE PRECISION NOT NULL,
    currency VARCHAR(10) DEFAULT 'EUR',
    category VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Time Record table
CREATE TABLE time_record (
    id BIGSERIAL PRIMARY KEY,
    activity VARCHAR(200) NOT NULL,
    category VARCHAR(100) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    duration_seconds BIGINT
);

-- Calendar Event table
CREATE TABLE calendar_event (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    all_day BOOLEAN NOT NULL DEFAULT false
);

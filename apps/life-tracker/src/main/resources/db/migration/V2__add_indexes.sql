-- Add performance indexes for common queries

-- Expense indexes
CREATE INDEX idx_expense_date ON expense(date);
CREATE INDEX idx_expense_category ON expense(category);
CREATE INDEX idx_expense_date_category ON expense(date, category);

-- Time Record indexes  
CREATE INDEX idx_time_record_start_time ON time_record(start_time);
CREATE INDEX idx_time_record_category ON time_record(category);
CREATE INDEX idx_time_record_start_category ON time_record(start_time, category);

-- Calendar Event indexes
CREATE INDEX idx_calendar_event_start_time ON calendar_event(start_time);
CREATE INDEX idx_calendar_event_end_time ON calendar_event(end_time);
CREATE INDEX idx_calendar_event_time_range ON calendar_event(start_time, end_time);

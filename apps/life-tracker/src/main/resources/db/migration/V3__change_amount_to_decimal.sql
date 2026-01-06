-- Change expense amount from DOUBLE to DECIMAL for precision
ALTER TABLE expense 
ALTER COLUMN amount TYPE DECIMAL(19,4);

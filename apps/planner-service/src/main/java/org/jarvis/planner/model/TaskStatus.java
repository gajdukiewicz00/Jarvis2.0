package org.jarvis.planner.model;

public enum TaskStatus {
    TODO,          // Не начата
    IN_PROGRESS,   // В работе
    DONE,          // Завершена
    CANCELLED,     // Отменена
    SKIPPED        // Occurrence skipped (recurring series continues; see Task#skippedAt)
}

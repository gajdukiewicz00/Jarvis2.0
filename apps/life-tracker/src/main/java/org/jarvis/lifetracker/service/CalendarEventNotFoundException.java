package org.jarvis.lifetracker.service;

public class CalendarEventNotFoundException extends RuntimeException {

    private final String userId;
    private final Long eventId;

    public CalendarEventNotFoundException(String userId, Long eventId) {
        super("Calendar event not found for user");
        this.userId = userId;
        this.eventId = eventId;
    }

    public String getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }
}

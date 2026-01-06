package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
}

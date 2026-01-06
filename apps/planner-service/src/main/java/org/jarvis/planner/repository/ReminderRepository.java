package org.jarvis.planner.repository;

import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.model.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    
    List<Reminder> findByUserIdAndStatus(String userId, ReminderStatus status);
    
    @Query("SELECT r FROM Reminder r WHERE r.status = 'ACTIVE' AND r.reminderTime <= :time")
    List<Reminder> findDueReminders(Instant time);
    
    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.reminderTime BETWEEN :start AND :end " +
           "AND r.status = 'ACTIVE' ORDER BY r.reminderTime ASC")
    List<Reminder> findUpcomingReminders(String userId, Instant start, Instant end);
}

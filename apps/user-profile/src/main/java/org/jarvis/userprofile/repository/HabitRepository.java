package org.jarvis.userprofile.repository;

import org.jarvis.userprofile.domain.Habit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findByUserId(String userId);
    List<Habit> findByUserIdAndFrequency(String userId, String frequency);
}

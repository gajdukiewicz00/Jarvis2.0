package org.jarvis.planner.repository;

import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    
    List<Task> findByUserIdAndStatus(String userId, TaskStatus status);
    
    List<Task> findByUserIdOrderByPriorityDescDueDateAsc(String userId);

    Optional<Task> findByIdAndUserId(Long id, String userId);

    long deleteByIdAndUserId(Long id, String userId);
    
    @Query("SELECT t FROM Task t WHERE t.userId = :userId AND t.status != 'DONE' AND t.status != 'CANCELLED' " +
           "ORDER BY t.priority DESC, t.dueDate ASC NULLS LAST")
    List<Task> findActiveTasks(String userId);
    
    @Query("SELECT t FROM Task t WHERE t.userId = :userId AND t.dueDate BETWEEN :start AND :end " +
           "AND t.status != 'DONE' AND t.status != 'CANCELLED'")
    List<Task> findTasksWithDeadlineBetween(String userId, Instant start, Instant end);
    
    long countByUserIdAndStatus(String userId, TaskStatus status);
}

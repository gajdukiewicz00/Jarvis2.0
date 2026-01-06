package org.jarvis.userprofile.repository;

import org.jarvis.userprofile.domain.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GoalRepository extends JpaRepository<Goal, Long> {
    List<Goal> findByUserId(String userId);
    List<Goal> findByUserIdAndStatus(String userId, String status);
    List<Goal> findByUserIdAndCategory(String userId, String category);
}

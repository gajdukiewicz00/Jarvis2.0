package org.jarvis.planner.repository;

import org.jarvis.planner.model.Recommendation;
import org.jarvis.planner.model.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    
    List<Recommendation> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, RecommendationStatus status);
    
    List<Recommendation> findByUserIdOrderByCreatedAtDesc(String userId);
}

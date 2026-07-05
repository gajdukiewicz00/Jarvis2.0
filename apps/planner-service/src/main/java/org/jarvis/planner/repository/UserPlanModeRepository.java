package org.jarvis.planner.repository;

import org.jarvis.planner.model.UserPlanMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPlanModeRepository extends JpaRepository<UserPlanMode, Long> {

    Optional<UserPlanMode> findByUserId(String userId);
}

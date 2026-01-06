package org.jarvis.userprofile.repository;

import org.jarvis.userprofile.domain.UserPriority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserPriorityRepository extends JpaRepository<UserPriority, Long> {
    List<UserPriority> findByUserId(String userId);
}

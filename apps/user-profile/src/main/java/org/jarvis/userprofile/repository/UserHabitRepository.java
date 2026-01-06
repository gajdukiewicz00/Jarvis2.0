package org.jarvis.userprofile.repository;

import org.jarvis.userprofile.domain.UserHabit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserHabitRepository extends JpaRepository<UserHabit, Long> {
    List<UserHabit> findByUserId(String userId);
}

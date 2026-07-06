package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.DraftStatus;
import org.jarvis.lifetracker.domain.ExpenseDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExpenseDraftRepository extends JpaRepository<ExpenseDraft, Long> {

    /** Paged review inbox listing (FINANCE-REVIEW): drafts awaiting a manual decision. */
    Page<ExpenseDraft> findByUserIdAndStatus(String userId, DraftStatus status, Pageable pageable);

    /** Ownership-scoped lookup used before edit/approve/reject so users can't touch each other's drafts. */
    Optional<ExpenseDraft> findByIdAndUserId(Long id, String userId);
}

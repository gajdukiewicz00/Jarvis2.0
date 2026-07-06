package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.DraftStatus;
import org.jarvis.lifetracker.domain.ExpenseDraft;
import org.jarvis.lifetracker.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class ExpenseDraftRepositoryTest {

    private static final String USER_ID = "user-1";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ExpenseDraftRepository draftRepository;

    private ExpenseDraft draft(String userId, DraftStatus status) {
        ExpenseDraft draft = new ExpenseDraft();
        draft.setUserId(userId);
        draft.setAmount(new BigDecimal("20.50"));
        draft.setCurrency("USD");
        draft.setType(TransactionType.EXPENSE);
        draft.setConfidence("MEDIUM");
        draft.setDedupKey("dedup-" + userId + "-" + status);
        draft.setOccurredAt(LocalDateTime.now());
        draft.setStatus(status);
        return draft;
    }

    @Test
    void findByUserIdAndStatusOnlyReturnsPendingDrafts() {
        ExpenseDraft pending = entityManager.persist(draft(USER_ID, DraftStatus.DRAFT));
        entityManager.persist(draft(USER_ID, DraftStatus.APPROVED));
        entityManager.persist(draft(USER_ID, DraftStatus.REJECTED));
        entityManager.persist(draft("other-user", DraftStatus.DRAFT));
        entityManager.flush();

        Page<ExpenseDraft> page = draftRepository.findByUserIdAndStatus(USER_ID, DraftStatus.DRAFT, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(pending.getId(), page.getContent().get(0).getId());
    }

    @Test
    void rejectedDraftDisappearsFromThePendingInboxQuery() {
        ExpenseDraft draft = entityManager.persistFlushFind(draft(USER_ID, DraftStatus.DRAFT));
        assertEquals(1, draftRepository.findByUserIdAndStatus(USER_ID, DraftStatus.DRAFT, PageRequest.of(0, 20))
                .getTotalElements());

        draft.setStatus(DraftStatus.REJECTED);
        entityManager.persistAndFlush(draft);

        assertEquals(0, draftRepository.findByUserIdAndStatus(USER_ID, DraftStatus.DRAFT, PageRequest.of(0, 20))
                .getTotalElements());
    }

    @Test
    void findByIdAndUserIdScopesLookupToOwner() {
        ExpenseDraft saved = entityManager.persistFlushFind(draft(USER_ID, DraftStatus.DRAFT));

        Optional<ExpenseDraft> found = draftRepository.findByIdAndUserId(saved.getId(), USER_ID);
        Optional<ExpenseDraft> wrongOwner = draftRepository.findByIdAndUserId(saved.getId(), "someone-else");

        assertTrue(found.isPresent());
        assertFalse(wrongOwner.isPresent());
    }
}

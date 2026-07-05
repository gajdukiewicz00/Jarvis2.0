package org.jarvis.planner.repository;

import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.UserPlanMode;
import org.jarvis.planner.support.PlannerPostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class UserPlanModeRepositoryPersistenceTest extends PlannerPostgresContainerSupport {

    @Autowired
    private UserPlanModeRepository userPlanModeRepository;

    @Test
    void findByUserIdReturnsThePersistedSelection() {
        UserPlanMode entity = new UserPlanMode();
        entity.setUserId("user-1");
        entity.setPlanMode(PlanMode.DEEP_WORK);
        userPlanModeRepository.save(entity);

        Optional<UserPlanMode> found = userPlanModeRepository.findByUserId("user-1");

        assertThat(found).isPresent();
        assertThat(found.get().getPlanMode()).isEqualTo(PlanMode.DEEP_WORK);
    }

    @Test
    void findByUserIdReturnsEmptyForUnknownUser() {
        assertThat(userPlanModeRepository.findByUserId("nobody")).isEmpty();
    }

    @Test
    void userIdIsUniqueAcrossPlanModeSelections() {
        UserPlanMode first = new UserPlanMode();
        first.setUserId("user-1");
        first.setPlanMode(PlanMode.NORMAL);
        userPlanModeRepository.saveAndFlush(first);

        UserPlanMode duplicate = new UserPlanMode();
        duplicate.setUserId("user-1");
        duplicate.setPlanMode(PlanMode.RECOVERY);

        assertThatThrownBy(() -> userPlanModeRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

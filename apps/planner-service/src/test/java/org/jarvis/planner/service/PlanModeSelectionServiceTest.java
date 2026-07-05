package org.jarvis.planner.service;

import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.UserPlanMode;
import org.jarvis.planner.repository.UserPlanModeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanModeSelectionServiceTest {

    @Mock
    private UserPlanModeRepository userPlanModeRepository;

    @InjectMocks
    private PlanModeSelectionService planModeSelectionService;

    @Test
    void getModeDefaultsToNormalWhenNoSelectionPersistedYet() {
        when(userPlanModeRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        PlanMode mode = planModeSelectionService.getMode("user-1");

        assertThat(mode).isEqualTo(PlanMode.NORMAL);
    }

    @Test
    void getModeReturnsThePersistedSelection() {
        UserPlanMode existing = new UserPlanMode();
        existing.setUserId("user-1");
        existing.setPlanMode(PlanMode.DEEP_WORK);
        when(userPlanModeRepository.findByUserId("user-1")).thenReturn(Optional.of(existing));

        PlanMode mode = planModeSelectionService.getMode("user-1");

        assertThat(mode).isEqualTo(PlanMode.DEEP_WORK);
    }

    @Test
    void setModeCreatesANewRowForAFirstTimeUser() {
        when(userPlanModeRepository.findByUserId("user-1")).thenReturn(Optional.empty());
        when(userPlanModeRepository.save(any(UserPlanMode.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanMode saved = planModeSelectionService.setMode("user-1", PlanMode.STUDY);

        assertThat(saved).isEqualTo(PlanMode.STUDY);
        ArgumentCaptor<UserPlanMode> captor = ArgumentCaptor.forClass(UserPlanMode.class);
        verify(userPlanModeRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getPlanMode()).isEqualTo(PlanMode.STUDY);
    }

    @Test
    void setModeUpdatesTheExistingRowInPlaceForAReturningUser() {
        UserPlanMode existing = new UserPlanMode();
        existing.setId(42L);
        existing.setUserId("user-1");
        existing.setPlanMode(PlanMode.NORMAL);
        when(userPlanModeRepository.findByUserId("user-1")).thenReturn(Optional.of(existing));
        when(userPlanModeRepository.save(any(UserPlanMode.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanMode saved = planModeSelectionService.setMode("user-1", PlanMode.RECOVERY);

        assertThat(saved).isEqualTo(PlanMode.RECOVERY);
        ArgumentCaptor<UserPlanMode> captor = ArgumentCaptor.forClass(UserPlanMode.class);
        verify(userPlanModeRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(42L); // same row, not a duplicate
        assertThat(captor.getValue().getPlanMode()).isEqualTo(PlanMode.RECOVERY);
    }

    @Test
    void setModeWithNullFallsBackToNormal() {
        when(userPlanModeRepository.findByUserId("user-1")).thenReturn(Optional.empty());
        when(userPlanModeRepository.save(any(UserPlanMode.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanMode saved = planModeSelectionService.setMode("user-1", null);

        assertThat(saved).isEqualTo(PlanMode.NORMAL);
    }
}

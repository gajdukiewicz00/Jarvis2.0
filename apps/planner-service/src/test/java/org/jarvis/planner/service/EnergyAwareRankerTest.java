package org.jarvis.planner.service;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskCategory;
import org.jarvis.planner.model.TaskPriority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyAwareRankerTest {

    private final EnergyAwareRanker ranker = new EnergyAwareRanker();

    private Task task(String title, TaskPriority priority, int durationMinutes) {
        Task t = new Task();
        t.setTitle(title);
        t.setPriority(priority);
        t.setEstimatedDuration(durationMinutes);
        return t;
    }

    @Test
    void exhaustedRanksLightOverHardEvenIfHigherPriority() {
        Task hard = task("Big refactor", TaskPriority.HIGH, 120);
        Task light = task("Reply email", TaskPriority.LOW, 10);

        List<Task> ranked = ranker.rank(List.of(hard, light), EnergyLevel.EXHAUSTED, false);

        assertThat(ranked.get(0)).isEqualTo(light); // exhausted -> light first, heavy avoided
    }

    @Test
    void highEnergyRanksHardFirst() {
        Task hard = task("Big refactor", TaskPriority.MEDIUM, 120);
        Task light = task("Reply email", TaskPriority.MEDIUM, 10);

        List<Task> ranked = ranker.rank(List.of(light, hard), EnergyLevel.HIGH, false);

        assertThat(ranked.get(0)).isEqualTo(hard);
    }

    @Test
    void normalEnergyIsPriorityDriven() {
        Task urgent = task("Urgent big", TaskPriority.URGENT, 120);
        Task minor = task("Minor quick", TaskPriority.LOW, 10);

        List<Task> ranked = ranker.rank(List.of(minor, urgent), EnergyLevel.NORMAL, false);

        assertThat(ranked.get(0)).isEqualTo(urgent);
    }

    @Test
    void forceOverridesEnergyAdjustment() {
        Task hard = task("Big refactor", TaskPriority.URGENT, 120);
        Task light = task("Reply email", TaskPriority.LOW, 10);

        List<Task> ranked = ranker.rank(List.of(light, hard), EnergyLevel.EXHAUSTED, true);

        assertThat(ranked.get(0)).isEqualTo(hard); // forced -> priority wins despite exhaustion
    }

    @Test
    void explanationDiffersByEnergy() {
        Task t = task("X", TaskPriority.HIGH, 60);
        assertThat(ranker.explain(t, EnergyLevel.EXHAUSTED)).contains("вымотан");
        assertThat(ranker.explain(t, EnergyLevel.HIGH)).contains("Высокая энергия");
    }

    @Test
    void fromTextMapsSpokenEnergyPhrases() {
        assertThat(EnergyLevel.fromText("я устал")).isEqualTo(EnergyLevel.LOW);
        assertThat(EnergyLevel.fromText("я выжат")).isEqualTo(EnergyLevel.EXHAUSTED);
        assertThat(EnergyLevel.fromText("полон сил")).isEqualTo(EnergyLevel.HIGH);
        assertThat(EnergyLevel.fromText("у меня норм")).isEqualTo(EnergyLevel.NORMAL);
        assertThat(EnergyLevel.fromText(null)).isEqualTo(EnergyLevel.NORMAL);
    }

    @Test
    void nearerDeadlineOutranksSamePriorityTaskWithLaterDeadline() {
        Task dueSoon = task("Report due soon", TaskPriority.MEDIUM, 30);
        dueSoon.setDueDate(Instant.now().plus(2, ChronoUnit.HOURS));
        Task dueLater = task("Report due later", TaskPriority.MEDIUM, 30);
        dueLater.setDueDate(Instant.now().plus(20, ChronoUnit.DAYS));

        List<Task> ranked = ranker.rank(List.of(dueLater, dueSoon), EnergyLevel.NORMAL, false);

        assertThat(ranked.get(0)).isEqualTo(dueSoon);
    }

    @Test
    void overdueTaskOutranksHigherPriorityTaskWithNoDeadline() {
        Task overdueMedium = task("Overdue medium", TaskPriority.MEDIUM, 30);
        overdueMedium.setDueDate(Instant.now().minus(1, ChronoUnit.HOURS));
        Task urgentNoDeadline = task("Urgent, no deadline", TaskPriority.URGENT, 30);

        List<Task> ranked = ranker.rank(List.of(urgentNoDeadline, overdueMedium), EnergyLevel.NORMAL, false);

        assertThat(ranked.get(0)).isEqualTo(overdueMedium);
    }

    @Test
    void deadlinePressureIsZeroWhenNoDueDate() {
        Task noDeadline = task("No deadline", TaskPriority.LOW, 30);
        assertThat(ranker.deadlinePressure(noDeadline)).isZero();
    }

    @Test
    void deepWorkPlanModeRanksHardTaskFirstEvenAtNormalEnergy() {
        Task hard = task("Big refactor", TaskPriority.MEDIUM, 120);
        Task light = task("Reply email", TaskPriority.MEDIUM, 10);

        List<Task> ranked = ranker.rank(List.of(light, hard), EnergyLevel.NORMAL, false, PlanMode.DEEP_WORK);

        assertThat(ranked.get(0)).isEqualTo(hard);
    }

    @Test
    void recoveryPlanModeRanksLightTaskFirstEvenIfLowerPriority() {
        Task hard = task("Big refactor", TaskPriority.HIGH, 120);
        Task light = task("Reply email", TaskPriority.LOW, 10);

        List<Task> ranked = ranker.rank(List.of(hard, light), EnergyLevel.NORMAL, false, PlanMode.RECOVERY);

        assertThat(ranked.get(0)).isEqualTo(light);
    }

    @Test
    void studyPlanModeFavoursStudyCategoryTasks() {
        Task study = task("Finish course module", TaskPriority.MEDIUM, 45);
        study.setCategory(TaskCategory.STUDY);
        Task work = task("Reply to email", TaskPriority.MEDIUM, 45);
        work.setCategory(TaskCategory.WORK);

        List<Task> ranked = ranker.rank(List.of(work, study), EnergyLevel.NORMAL, false, PlanMode.STUDY);

        assertThat(ranked.get(0)).isEqualTo(study);
    }

    @Test
    void minimumViableDayPlanModeFavoursEssentialTasksOverRoutineOnes() {
        Task urgent = task("Fix outage", TaskPriority.URGENT, 30);
        Task routine = task("Read newsletter", TaskPriority.HIGH, 10);

        List<Task> ranked = ranker.rank(List.of(routine, urgent), EnergyLevel.NORMAL, false,
                PlanMode.MINIMUM_VIABLE_DAY);

        assertThat(ranked.get(0)).isEqualTo(urgent);
    }

    @Test
    void normalPlanModeAddsNoAdjustmentOnTopOfEnergy() {
        Task urgent = task("Urgent big", TaskPriority.URGENT, 120);
        Task minor = task("Minor quick", TaskPriority.LOW, 10);

        List<Task> ranked = ranker.rank(List.of(minor, urgent), EnergyLevel.NORMAL, false, PlanMode.NORMAL);

        assertThat(ranked.get(0)).isEqualTo(urgent);
    }

    @Test
    void forceOverridesPlanModeAdjustmentJustLikeEnergy() {
        Task hard = task("Big refactor", TaskPriority.URGENT, 120);
        Task light = task("Reply email", TaskPriority.LOW, 10);

        List<Task> ranked = ranker.rank(List.of(light, hard), EnergyLevel.NORMAL, true, PlanMode.RECOVERY);

        assertThat(ranked.get(0)).isEqualTo(hard); // forced -> priority wins despite recovery mode
    }

    @Test
    void deadlineLabelClassifiesByProximity() {
        Task noDeadline = task("x", TaskPriority.LOW, 10);
        assertThat(ranker.deadlineLabel(noDeadline)).isEqualTo("NONE");

        Task overdue = task("x", TaskPriority.LOW, 10);
        overdue.setDueDate(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(ranker.deadlineLabel(overdue)).isEqualTo("OVERDUE");

        Task dueToday = task("x", TaskPriority.LOW, 10);
        dueToday.setDueDate(Instant.now().plus(2, ChronoUnit.HOURS));
        assertThat(ranker.deadlineLabel(dueToday)).isEqualTo("DUE_TODAY");

        Task dueSoon = task("x", TaskPriority.LOW, 10);
        dueSoon.setDueDate(Instant.now().plus(48, ChronoUnit.HOURS));
        assertThat(ranker.deadlineLabel(dueSoon)).isEqualTo("DUE_SOON");

        Task dueThisWeek = task("x", TaskPriority.LOW, 10);
        dueThisWeek.setDueDate(Instant.now().plus(5, ChronoUnit.DAYS));
        assertThat(ranker.deadlineLabel(dueThisWeek)).isEqualTo("DUE_THIS_WEEK");

        Task later = task("x", TaskPriority.LOW, 10);
        later.setDueDate(Instant.now().plus(30, ChronoUnit.DAYS));
        assertThat(ranker.deadlineLabel(later)).isEqualTo("LATER");
    }
}

package org.jarvis.planner.service;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.junit.jupiter.api.Test;

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
}

package org.jarvis.planner.repository;

import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskSource;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.support.PlannerPostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class TaskRepositoryPersistenceTest extends PlannerPostgresContainerSupport {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findActiveTasksFiltersClosedStatusesAndUsesCurrentPostgresPriorityOrdering() {
        taskRepository.save(newTask("user-1", "medium-later", TaskPriority.MEDIUM, TaskStatus.TODO,
                Instant.parse("2026-03-15T09:00:00Z"), List.of("deep-work"), TaskSource.MANUAL));
        taskRepository.save(newTask("user-1", "high-earlier", TaskPriority.HIGH, TaskStatus.TODO,
                Instant.parse("2026-03-10T09:00:00Z"), List.of("focus"), TaskSource.AI));
        taskRepository.save(newTask("user-1", "high-no-deadline", TaskPriority.HIGH, TaskStatus.IN_PROGRESS,
                null, List.of("backlog"), TaskSource.AI));
        taskRepository.save(newTask("user-1", "done-ignored", TaskPriority.URGENT, TaskStatus.DONE,
                Instant.parse("2026-03-09T09:00:00Z"), List.of("ignore"), TaskSource.MANUAL));
        taskRepository.save(newTask("user-1", "cancelled-ignored", TaskPriority.URGENT, TaskStatus.CANCELLED,
                Instant.parse("2026-03-08T09:00:00Z"), List.of("ignore"), TaskSource.MANUAL));
        taskRepository.save(newTask("user-2", "other-user", TaskPriority.URGENT, TaskStatus.TODO,
                Instant.parse("2026-03-07T09:00:00Z"), List.of("foreign"), TaskSource.MANUAL));

        List<String> titles = taskRepository.findActiveTasks("user-1").stream()
                .map(Task::getTitle)
                .toList();

        assertThat(titles).containsExactly("medium-later", "high-earlier", "high-no-deadline");
    }

    @Test
    void findTasksWithDeadlineBetweenIsUserScopedInclusiveAndSkipsClosedStatuses() {
        Instant start = Instant.parse("2026-03-10T00:00:00Z");
        Instant end = Instant.parse("2026-03-20T23:59:59Z");

        taskRepository.save(newTask("user-1", "start-boundary", TaskPriority.LOW, TaskStatus.TODO,
                start, List.of("a"), TaskSource.MANUAL));
        taskRepository.save(newTask("user-1", "end-boundary", TaskPriority.LOW, TaskStatus.IN_PROGRESS,
                end, List.of("b"), TaskSource.MANUAL));
        taskRepository.save(newTask("user-1", "done-inside-window", TaskPriority.URGENT, TaskStatus.DONE,
                Instant.parse("2026-03-15T10:00:00Z"), List.of("c"), TaskSource.AI));
        taskRepository.save(newTask("user-1", "before-window", TaskPriority.URGENT, TaskStatus.TODO,
                Instant.parse("2026-03-09T23:59:59Z"), List.of("d"), TaskSource.AI));
        taskRepository.save(newTask("user-2", "other-user", TaskPriority.URGENT, TaskStatus.TODO,
                Instant.parse("2026-03-15T10:00:00Z"), List.of("e"), TaskSource.AI));

        List<String> titles = taskRepository.findTasksWithDeadlineBetween("user-1", start, end).stream()
                .map(Task::getTitle)
                .toList();

        assertThat(titles).containsExactlyInAnyOrder("start-boundary", "end-boundary");
    }

    @Test
    void findByIdAndUserIdAndDeleteByIdAndUserIdAreUserScoped() {
        Task task = taskRepository.saveAndFlush(newTask("user-1", "scoped-task", TaskPriority.HIGH, TaskStatus.TODO,
                Instant.parse("2026-03-12T09:00:00Z"), List.of("scope"), TaskSource.MANUAL));

        assertThat(taskRepository.findByIdAndUserId(task.getId(), "user-2")).isEmpty();
        assertThat(taskRepository.findByIdAndUserId(task.getId(), "user-1")).isPresent();
        assertThat(taskRepository.deleteByIdAndUserId(task.getId(), "user-2")).isZero();
        assertThat(taskRepository.deleteByIdAndUserId(task.getId(), "user-1")).isEqualTo(1);
        assertThat(taskRepository.findById(task.getId())).isEmpty();
    }

    @Test
    void saveAndLoadRoundTripsTrimmedTagsAndMetadata() {
        Task task = newTask("user-1", "tagged-task", TaskPriority.URGENT, TaskStatus.TODO,
                Instant.parse("2026-03-18T09:00:00Z"), List.of(" alpha ", "beta", "   "), TaskSource.AI);
        task.setCreatedBy("tool-todo");
        task.setUpdatedBy("tool-todo");

        Long id = taskRepository.saveAndFlush(task).getId();
        entityManager.clear();

        Task reloaded = taskRepository.findById(id).orElseThrow();

        assertThat(reloaded.getTags()).containsExactly("alpha", "beta");
        assertThat(reloaded.getSource()).isEqualTo(TaskSource.AI);
        assertThat(reloaded.getCreatedBy()).isEqualTo("tool-todo");
        assertThat(reloaded.getUpdatedBy()).isEqualTo("tool-todo");
    }

    @Test
    void blankOnlyTagsCurrentlyPersistAsEmptyStringAndReloadAsEmptyList() {
        Long id = taskRepository.saveAndFlush(newTask("user-1", "blank-tags", TaskPriority.LOW, TaskStatus.TODO,
                Instant.parse("2026-03-19T09:00:00Z"), List.of("   ", ""), TaskSource.MANUAL)).getId();

        String rawTags = jdbcTemplate.queryForObject(
                "select tags from planner.tasks where id = ?",
                String.class,
                id);
        entityManager.clear();

        Task reloaded = taskRepository.findById(id).orElseThrow();

        assertThat(rawTags).isEmpty();
        assertThat(reloaded.getTags()).isEmpty();
    }

    private Task newTask(String userId, String title, TaskPriority priority, TaskStatus status, Instant dueDate,
            List<String> tags, TaskSource source) {
        Task task = new Task();
        task.setUserId(userId);
        task.setTitle(title);
        task.setPriority(priority);
        task.setStatus(status);
        task.setDueDate(dueDate);
        task.setTags(tags);
        task.setSource(source);
        task.setEstimatedDuration(30);
        return task;
    }
}

package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug hunt #14 (memory-service — logic-error): {@code searchByText}'s and
 * {@code findSimilarNoteIds}'s "exclude deleted" filter compared the note
 * status against the lowercase literal {@code 'deleted'}, but {@code
 * MemoryForgetService.forget()} persists the status as uppercase {@code
 * 'DELETED'} (a plain, case-sensitive TEXT column). Since {@code 'DELETED'
 * <> 'deleted'} is true, the exclusion never filtered anything and forgotten
 * notes kept surfacing in keyword/semantic search.
 *
 * <p>Reflects on the {@code @Query} annotation text directly (no database
 * required) so this test fails against the original lowercase literal and
 * passes once the literal is corrected to match the persisted uppercase
 * value.</p>
 */
class MemoryNoteRepositoryQueryLiteralTest {

    @Test
    void searchByTextExcludesUppercaseDeletedStatusNotLowercase() throws NoSuchMethodException {
        Method method = MemoryNoteRepository.class.getMethod(
                "searchByText", String.class, Pageable.class);
        String jpql = method.getAnnotation(Query.class).value();

        assertThat(jpql)
                .as("searchByText JPQL must compare against the actually-persisted uppercase 'DELETED' literal")
                .contains("'DELETED'")
                .doesNotContain("'deleted'");
    }

    @Test
    void findSimilarNoteIdsExcludesUppercaseDeletedStatusNotLowercase() throws NoSuchMethodException {
        Method method = MemoryNoteRepository.class.getMethod(
                "findSimilarNoteIds", String.class, double.class, int.class);
        String sql = method.getAnnotation(Query.class).value();

        assertThat(sql)
                .as("findSimilarNoteIds native query must compare against the uppercase 'DELETED' literal")
                .contains("'DELETED'")
                .doesNotContain("'deleted'");
    }
}

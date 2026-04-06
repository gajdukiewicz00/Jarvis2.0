package org.jarvis.planner.service;

import org.jarvis.planner.client.LlmServiceClient;
import org.jarvis.planner.dto.DailyPlanDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmEnhancementServiceTest {

    @Test
    void enhancePlanDescriptionDelegatesOnlyWhenExplicitlyInvoked() {
        LlmServiceClient client = mock(LlmServiceClient.class);
        when(client.enhancePlanDescription("user-1", "План на день:\nfocus: Ship cleanup\n"))
                .thenReturn("Enhanced");

        DailyPlanDto plan = new DailyPlanDto();
        Map<String, java.util.List<String>> blocks = new LinkedHashMap<>();
        blocks.put("focus", java.util.List.of("Ship cleanup"));
        plan.setBlocks(blocks);

        LlmEnhancementService service = new LlmEnhancementService(client);

        assertEquals("Enhanced", service.enhancePlanDescription("user-1", plan));
    }

    @Test
    void optionalPlaceholderOperationsAreExplicitlyUnsupported() {
        LlmEnhancementService service = new LlmEnhancementService(mock(LlmServiceClient.class));

        assertThrows(UnsupportedOperationException.class,
                () -> service.generateDocument("user-1", "email", "context"));
        assertThrows(UnsupportedOperationException.class,
                () -> service.generateSmartRecommendation("user-1", "context"));
        assertThrows(UnsupportedOperationException.class,
                () -> service.parseNaturalLanguageTask("user-1", "Напомни про встречу"));
    }
}

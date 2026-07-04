package org.jarvis.llm.orchestrator.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationConfidenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPlanAndPerCallConfidence() throws Exception {
        String json = """
                {
                  "explanation": "ok",
                  "confidence": 0.84,
                  "tool_calls": [
                    {"name": "create_todo", "arguments": {"title": "x"}, "confidence": 0.91}
                  ]
                }
                """;

        ModelToolPlan plan = mapper.readValue(json, ModelToolPlan.class);

        assertThat(plan.getConfidence()).isEqualTo(0.84);
        assertThat(plan.getToolCalls()).hasSize(1);
        assertThat(plan.getToolCalls().get(0).getConfidence()).isEqualTo(0.91);
    }

    @Test
    void confidenceIsNullWhenModelOmitsIt() throws Exception {
        ModelToolPlan plan = mapper.readValue(
                "{\"explanation\":\"ok\",\"tool_calls\":[]}", ModelToolPlan.class);
        assertThat(plan.getConfidence()).isNull();
    }
}

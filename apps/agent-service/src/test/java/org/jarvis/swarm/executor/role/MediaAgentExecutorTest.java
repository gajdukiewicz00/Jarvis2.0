package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.permission.PermissionDeniedException;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaAgentExecutorTest {

    @TempDir
    Path tmp;

    private ExecutionContext ctx(String goal, Set<ToolPermission> granted) {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("media-" + Math.abs(goal.hashCode()));
        AgentTask task = SwarmTestFactory.task(AgentRole.MEDIA, goal, Set.of(ToolPermission.MEDIA_ACCESS),
                granted, false);
        return SwarmTestFactory.context(task, sb, engine.guard());
    }

    @Test
    void rejectedWithoutMediaAccessGrant() {
        assertThatThrownBy(() -> new MediaAgentExecutor().execute(ctx("make subtitles", Set.of())))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void subtitleGoalProducesSubtitleJobSpec() {
        RoleResult result = new MediaAgentExecutor()
                .execute(ctx("add subtitles to the clip", Set.of(ToolPermission.MEDIA_ACCESS)));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("russian-subtitles");
        assertThat(result.output()).contains("/api/v1/media/jobs/russian-subtitles");
    }

    @Test
    void russianSubtitleKeywordAlsoMatchesSubtitleBranch() {
        RoleResult result = new MediaAgentExecutor()
                .execute(ctx("сделай субтитры", Set.of(ToolPermission.MEDIA_ACCESS)));

        assertThat(result.summary()).contains("russian-subtitles");
    }

    @Test
    void dubGoalProducesDubJobSpec() {
        RoleResult result = new MediaAgentExecutor()
                .execute(ctx("dub this video into russian", Set.of(ToolPermission.MEDIA_ACCESS)));

        assertThat(result.summary()).contains("russian-dub-audio");
        assertThat(result.output()).contains("/api/v1/media/jobs/russian-dub-audio");
    }

    @Test
    void russianDubKeywordAlsoMatchesDubBranch() {
        RoleResult result = new MediaAgentExecutor()
                .execute(ctx("нужна озвучка ролика", Set.of(ToolPermission.MEDIA_ACCESS)));

        assertThat(result.summary()).contains("russian-dub-audio");
    }

    @Test
    void unrecognizedGoalFallsBackToProbeAndEscapesQuotes() {
        RoleResult result = new MediaAgentExecutor()
                .execute(ctx("inspect the \"input\" file", Set.of(ToolPermission.MEDIA_ACCESS)));

        assertThat(result.summary()).contains("probe");
        assertThat(result.output()).contains("/api/v1/media/probe");
        assertThat(result.output()).contains("'input'");
        assertThat(result.nextActions()).anyMatch(n -> n.contains("media-service"));
    }
}

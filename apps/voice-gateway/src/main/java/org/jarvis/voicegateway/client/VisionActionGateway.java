package org.jarvis.voicegateway.client;

import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher;

/**
 * Bridge to the vision "analyze what's on screen" capability.
 *
 * <p>voice-gateway is NetworkPolicy-blocked from reaching vision-service directly, so the
 * implementation posts to the orchestrator's {@code /internal/vision/ask-screen} passthrough
 * (the same reachable pattern used by the PC-control and planner gateways).
 *
 * <p>Returns a ready-to-consume {@link VoiceCommandActionDispatcher.DispatchResult} so the
 * dispatcher's VISION branch and the confirm-execution path reuse the exact same override /
 * failure-message handling as every other voice action.
 */
public interface VisionActionGateway {

    /**
     * Asks the vision pipeline to describe the current screen.
     *
     * @param userId        the end-user whose desktop screen to analyze
     * @param question      the natural-language question (e.g. "Что на экране?")
     * @param correlationId trace id for logs
     * @return a dispatch result: on success {@code responseTextOverride} carries the spoken answer;
     *         on failure {@code failureReason} carries a coded reason
     *         ({@code VISION_UNAVAILABLE} / {@code VISION_HTTP_x} / {@code VISION_EMPTY})
     */
    VoiceCommandActionDispatcher.DispatchResult askScreen(String userId, String question, String correlationId);
}

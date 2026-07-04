package org.jarvis.orchestrator.voice;

import org.jarvis.commands.CommandResult;
import org.jarvis.commands.CommandStatus;
import org.jarvis.commands.voice.VoiceFeedback;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Phase 7 — Iron-Man-Jarvis-styled spoken responses.
 *
 * <p>SPEC-1 § "Must Have": "Jarvis must always provide voice feedback for
 * completed or rejected actions" and "Jarvis must communicate in a style
 * close to Iron Man Jarvis: calm, intelligent, concise, slightly formal."
 * Every {@link CommandResult} status maps to a deterministic phrase here
 * so the user never gets silence after issuing a command.</p>
 *
 * <p>Russian locale is the project default; an English variant exists for
 * fallback. Phrases are intentionally short — voice latency matters more
 * than wit.</p>
 */
@Component
public class VoiceFeedbackTemplates {

    public VoiceFeedback success() {
        return build("SUCCESS", VoiceFeedback.Level.INFO, "Готово, сэр.", "Готово, сэр.");
    }

    /**
     * Success feedback that carries the orchestrator's own spoken response
     * (e.g. "Уменьшаю громкость, сэр.") instead of the generic "Готово".
     * Used by the synchronous voice fast-path so the user hears the real
     * confirmation phrase produced by the executed intent.
     */
    public VoiceFeedback executed(String spokenText) {
        if (spokenText == null || spokenText.isBlank()) {
            return success();
        }
        String text = spokenText.length() > 240 ? spokenText.substring(0, 237) + "..." : spokenText;
        return build("SUCCESS", VoiceFeedback.Level.INFO, text, text);
    }

    public VoiceFeedback cancelled() {
        return build("CANCELLED", VoiceFeedback.Level.INFO, "Отменено, сэр.", "Отменено, сэр.");
    }

    public VoiceFeedback awaitingConfirmation() {
        return build("AWAITING_CONFIRMATION", VoiceFeedback.Level.WARN,
                "Действие требует подтверждения, сэр.",
                "Действие требует подтверждения, сэр.");
    }

    public VoiceFeedback denied(String reason) {
        return build("DENIED", VoiceFeedback.Level.WARN,
                "Отклонено, сэр. Действие не выполнено.",
                concise("Отклонено, сэр.", reason));
    }

    public VoiceFeedback timeout() {
        return build("TIMEOUT", VoiceFeedback.Level.WARN,
                "Таймаут подтверждения, сэр. Действие не выполнено.",
                "Таймаут подтверждения. Действие отменено.");
    }

    public VoiceFeedback expired() {
        return build("EXPIRED", VoiceFeedback.Level.WARN,
                "Команда устарела, сэр. Не выполняю.",
                "Срок действия команды истёк.");
    }

    public VoiceFeedback rejectedNonOwner(String speaker, String owner) {
        return build("BLOCKED_NON_OWNER", VoiceFeedback.Level.ERROR,
                "Это действие требует подтверждения владельца, сэр.",
                "Не-владелец '" + speaker + "' попытался подтвердить (ожидался '" + owner + "').");
    }

    public VoiceFeedback rejectedDemoMode() {
        return build("BLOCKED_DEMO_MODE", VoiceFeedback.Level.WARN,
                "Демо-режим, сэр. Привилегированные действия недоступны.",
                "Демо-режим заблокировал привилегированное действие.");
    }

    public VoiceFeedback failed(String reason) {
        return build("FAILED", VoiceFeedback.Level.ERROR,
                "Не удалось выполнить, сэр. Подробности в журнале.",
                concise("Ошибка выполнения, сэр.", reason));
    }

    public VoiceFeedback degraded(String component) {
        return build("DEGRADED", VoiceFeedback.Level.WARN,
                "Частичный режим, сэр: " + component + " недоступен.",
                "Деградация: " + component + " недоступен.");
    }

    public VoiceFeedback clarify(String reason) {
        return build("CLARIFY", VoiceFeedback.Level.INFO,
                "Уточните, пожалуйста, что именно нужно сделать.",
                concise("Уточните, пожалуйста.", reason));
    }

    public VoiceFeedback unknownIntent(String transcript) {
        return build("UNKNOWN_INTENT", VoiceFeedback.Level.WARN,
                "Не понял команду, сэр. Повторите, пожалуйста.",
                "Интент не распознан: \"" + safe(transcript) + "\"");
    }

    public VoiceFeedback processing() {
        return build("PROCESSING", VoiceFeedback.Level.INFO,
                "Минуту, сэр.",
                "Обработка...");
    }

    /**
     * Map a {@link CommandResult} to the matching feedback. Used by the
     * voice-loop controller after dispatch returns.
     */
    public VoiceFeedback fromCommandResult(CommandResult result) {
        if (result == null) {
            return failed("no result");
        }
        CommandStatus status = result.getStatus();
        if (status == null) {
            return failed(safe(result.getErrorReason()));
        }
        return switch (status) {
            case SUCCESS -> success();
            case AWAITING_CONFIRMATION -> awaitingConfirmation();
            case REJECTED -> mapRejected(result.getErrorReason());
            case EXPIRED -> expired();
            case FAILED -> failed(safe(result.getErrorReason()));
            case CREATED, QUEUED, EXECUTING -> processing();
        };
    }

    private VoiceFeedback mapRejected(String reason) {
        if (reason == null) return denied(null);
        String upper = reason.toUpperCase(Locale.ROOT);
        if (upper.contains("BLOCKED_DEMO_MODE")) return rejectedDemoMode();
        if (upper.contains("BLOCKED_NON_OWNER")) return rejectedNonOwner("?", "?");
        if (upper.contains("TIMEOUT")) return timeout();
        if (upper.contains("DENIED")) return denied(reason);
        return denied(reason);
    }

    private VoiceFeedback build(String code, VoiceFeedback.Level level,
                                 String spoken, String display) {
        return VoiceFeedback.builder()
                .code(code)
                .level(level)
                .spokenText(spoken)
                .displayText(display)
                .build();
    }

    private String concise(String prefix, String detail) {
        if (detail == null || detail.isBlank()) {
            return prefix;
        }
        String trimmed = detail.length() > 120 ? detail.substring(0, 117) + "..." : detail;
        return prefix + " " + trimmed;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}

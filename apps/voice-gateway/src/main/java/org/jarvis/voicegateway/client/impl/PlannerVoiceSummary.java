package org.jarvis.voicegateway.client.impl;

/**
 * Pure, side-effect-free builder for the spoken planner summary. Kept separate
 * from {@link RestPlannerActionGateway} so the Russian pluralisation and the
 * "no tasks" / "with focus" branches can be unit-tested without any HTTP stubs.
 */
final class PlannerVoiceSummary {

    private PlannerVoiceSummary() {}

    static String build(boolean ru, int openTasks, String title, String message) {
        boolean hasTitle = title != null && !title.isBlank();
        if (openTasks <= 0) {
            if (hasTitle) {
                return ru
                        ? "Сэр, открытых задач на сегодня нет. Главный фокус: " + title + "."
                        : "Sir, no open tasks for today. Main focus: " + title + ".";
            }
            return ru
                    ? "Сэр, на сегодня открытых задач нет — можете выдохнуть."
                    : "Sir, there are no open tasks for today — you can relax.";
        }
        if (ru) {
            String head = "Сэр, сегодня у вас " + openTasks + " " + pluralTasksRu(openTasks) + ".";
            if (hasTitle) {
                return head + " Главный фокус: " + title + ".";
            }
            String fallback = message != null && !message.isBlank() ? message : null;
            return fallback != null ? head + " " + fallback : head;
        }
        String head = "Sir, you have " + openTasks + (openTasks == 1 ? " open task" : " open tasks") + " today.";
        if (hasTitle) {
            return head + " Main focus: " + title + ".";
        }
        return head;
    }

    /** Russian plural of "задача": 1→задача, 2-4→задачи, else→задач (11-14 → задач). */
    static String pluralTasksRu(int n) {
        int mod100 = Math.abs(n) % 100;
        int mod10 = mod100 % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return "задач";
        }
        if (mod10 == 1) {
            return "задача";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return "задачи";
        }
        return "задач";
    }
}

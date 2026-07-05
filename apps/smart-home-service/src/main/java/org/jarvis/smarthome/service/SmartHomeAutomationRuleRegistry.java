package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry of automation rules (in-memory), evaluated by {@link SmartHomeAutomationEngine}. */
@Component
public class SmartHomeAutomationRuleRegistry {

    private final Map<String, SmartHomeAutomationRule> rules = new ConcurrentHashMap<>();

    /** Create or fully replace a rule. */
    public SmartHomeAutomationRule save(SmartHomeAutomationRule rule) {
        rules.put(rule.id(), rule);
        return rule;
    }

    public List<SmartHomeAutomationRule> all() {
        return List.copyOf(rules.values());
    }

    public Optional<SmartHomeAutomationRule> find(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public boolean remove(String ruleId) {
        return rules.remove(ruleId) != null;
    }
}

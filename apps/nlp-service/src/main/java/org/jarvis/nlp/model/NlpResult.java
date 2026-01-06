package org.jarvis.nlp.model;

import java.util.Map;

public record NlpResult(String intent, Map<String, String> slots) {}

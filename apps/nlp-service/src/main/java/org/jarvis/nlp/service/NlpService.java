package org.jarvis.nlp.service;

import org.jarvis.nlp.model.NlpResult;

public interface NlpService {
    NlpResult infer(String text, String languageCode);
}

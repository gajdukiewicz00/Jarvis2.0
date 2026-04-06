package org.jarvis.voicegateway.client;

import java.util.Map;

public interface PcControlActionGateway {

    void dispatch(String action, Map<String, Object> params, String userId);
}

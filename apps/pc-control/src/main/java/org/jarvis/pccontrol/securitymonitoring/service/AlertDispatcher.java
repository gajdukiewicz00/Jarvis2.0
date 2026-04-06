package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.pccontrol.securitymonitoring.model.AlertPayload;

public interface AlertDispatcher {

    void dispatch(AlertPayload payload) throws Exception;
}

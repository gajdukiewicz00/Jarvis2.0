package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.pccontrol.securitymonitoring.model.WorkstationMetadata;

public interface WorkstationMetadataProvider {

    WorkstationMetadata collect();
}

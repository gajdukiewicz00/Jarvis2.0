package org.jarvis.pccontrol.service;

import java.util.List;
import java.util.Map;

public interface FileService {
    List<String> listFiles(String path) throws Exception;

    String readFile(String path) throws Exception;

    Map<String, Object> getFileInfo(String path) throws Exception;
}

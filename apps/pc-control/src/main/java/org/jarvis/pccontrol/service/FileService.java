package org.jarvis.pccontrol.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface FileService {
    List<String> listFiles(String path) throws IOException;

    String readFile(String path) throws IOException;

    Map<String, Object> getFileInfo(String path) throws IOException;
}

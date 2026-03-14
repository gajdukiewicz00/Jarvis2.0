package org.jarvis.llm.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSchemaRegistryTest {

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private Resource resource;

    @Test
    void renderForPromptLoadsRegistryOnceAndCachesIt() {
        ToolSchemaRegistry registry = new ToolSchemaRegistry(resourceLoader);
        ByteArrayResource byteArrayResource =
                new ByteArrayResource("[{\"name\":\"todo.create\"}]".getBytes(StandardCharsets.UTF_8));

        when(resourceLoader.getResource(anyString())).thenReturn(byteArrayResource);

        assertEquals("[{\"name\":\"todo.create\"}]", registry.renderForPrompt());
        assertEquals("[{\"name\":\"todo.create\"}]", registry.renderForPrompt());
        verify(resourceLoader, times(1)).getResource(anyString());
    }

    @Test
    void renderForPromptReturnsEmptyRegistryWhenResourceIsMissing() {
        ToolSchemaRegistry registry = new ToolSchemaRegistry(resourceLoader);

        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.exists()).thenReturn(false);

        assertEquals("[]", registry.renderForPrompt());
    }

    @Test
    void renderForPromptReturnsEmptyRegistryWhenResourceReadFails() throws IOException {
        ToolSchemaRegistry registry = new ToolSchemaRegistry(resourceLoader);

        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream()).thenThrow(new IOException("broken stream"));

        assertEquals("[]", registry.renderForPrompt());
    }
}

package org.jarvis.llm.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemPromptProviderTest {

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private Resource resource;

    @Test
    void getPromptLoadsClasspathPromptOnceAndCachesIt() throws IOException {
        SystemPromptProvider provider = new SystemPromptProvider(resourceLoader);
        ByteArrayResource byteArrayResource =
                new ByteArrayResource("system prompt".getBytes(StandardCharsets.UTF_8));

        when(resourceLoader.getResource(anyString())).thenReturn(byteArrayResource);

        assertEquals("system prompt", provider.getPrompt());
        assertEquals("system prompt", provider.getPrompt());
        verify(resourceLoader, times(1)).getResource(anyString());
    }

    @Test
    void getPromptReturnsEmptyStringWhenPromptResourceIsMissing() {
        SystemPromptProvider provider = new SystemPromptProvider(resourceLoader);

        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.exists()).thenReturn(false);

        assertEquals("", provider.getPrompt());
    }

    @Test
    void getPromptReturnsEmptyStringWhenPromptResourceCannotBeRead() throws IOException {
        SystemPromptProvider provider = new SystemPromptProvider(resourceLoader);

        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream()).thenThrow(new IOException("broken stream"));

        assertEquals("", provider.getPrompt());
    }
}

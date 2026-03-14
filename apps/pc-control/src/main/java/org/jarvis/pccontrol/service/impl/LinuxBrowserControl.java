package org.jarvis.pccontrol.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LinuxBrowserControl {

    private static final Set<String> GENERIC_BROWSER_ALIASES = Set.of("browser", "web browser", "default browser");

    private final CommandExecutor commandExecutor;
    private final CommandLocator commandLocator;
    private final DesktopControlProperties properties;

    public DesktopOperationResponse openUrl(String url, String browser) throws IOException {
        String validatedUrl = validateUrl(url);
        if (browser == null || browser.isBlank()) {
            if (!commandLocator.isAvailable("xdg-open")) {
                throw new MissingToolException("xdg-open");
            }
            commandExecutor.start(List.of("xdg-open", validatedUrl));
            return new DesktopOperationResponse(
                    true,
                    "open_url",
                    "URL opened in the default browser",
                    Map.of("url", validatedUrl, "browser", "default"),
                    null);
        }

        BrowserInstallation installation = resolveInstalledBrowser(browser)
                .orElseThrow(() -> new IllegalArgumentException("Browser not available: " + browser));
        commandExecutor.start(List.of(installation.command(), validatedUrl));
        return new DesktopOperationResponse(
                true,
                "open_url",
                "URL opened in the requested browser",
                Map.of("url", validatedUrl, "browser", installation.command()),
                null);
    }

    public DesktopOperationResponse launchBrowser(String browser) throws IOException {
        Optional<BrowserInstallation> installation = browser == null || browser.isBlank()
                ? installedBrowsers().stream().findFirst()
                : resolveInstalledBrowser(browser);
        BrowserInstallation resolved = installation
                .orElseThrow(() -> new MissingToolException("browser"));
        commandExecutor.start(List.of(resolved.command()));
        return new DesktopOperationResponse(
                true,
                "open_app",
                "Browser launched",
                Map.of("name", resolved.displayName(), "desktopId", resolved.command()),
                null);
    }

    public List<String> detectInstalledBrowsers() {
        return installedBrowsers().stream()
                .map(BrowserInstallation::displayName)
                .toList();
    }

    public boolean isGenericBrowserAlias(String value) {
        if (value == null) {
            return false;
        }
        return GENERIC_BROWSER_ALIASES.contains(normalize(value));
    }

    private List<BrowserInstallation> installedBrowsers() {
        Map<String, BrowserInstallation> installations = new LinkedHashMap<>();
        for (String candidate : properties.getBrowserCandidates()) {
            if (commandLocator.isAvailable(candidate)) {
                installations.putIfAbsent(candidate, new BrowserInstallation(candidate, displayName(candidate)));
            }
        }
        return List.copyOf(installations.values());
    }

    private Optional<BrowserInstallation> resolveInstalledBrowser(String requested) {
        String normalized = normalize(requested);
        return installedBrowsers().stream()
                .filter(browser -> normalize(browser.command()).equals(normalized)
                        || normalize(browser.displayName()).equals(normalized))
                .findFirst();
    }

    private static String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("URL host is required");
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
    }

    private static String displayName(String command) {
        return switch (command) {
            case "google-chrome" -> "Google Chrome";
            case "brave-browser" -> "Brave Browser";
            case "microsoft-edge", "microsoft-edge-stable" -> "Microsoft Edge";
            default -> command.substring(0, 1).toUpperCase(Locale.ROOT) + command.substring(1);
        };
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    private record BrowserInstallation(String command, String displayName) {
    }
}

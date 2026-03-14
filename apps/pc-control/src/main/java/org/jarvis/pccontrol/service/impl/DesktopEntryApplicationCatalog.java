package org.jarvis.pccontrol.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.model.DesktopApplication;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DesktopEntryApplicationCatalog {

    private static final Pattern SAFE_QUERY_PATTERN = Pattern.compile("^[\\p{L}\\p{N} ._+-]{1,120}$");
    private static final Pattern EXEC_FIELD_CODES = Pattern.compile("%[%fFuUdDnNickvm]");

    private final DesktopControlProperties properties;

    public List<DesktopApplication> listApplications() {
        return loadTargets().stream()
                .map(DesktopLaunchTarget::application)
                .sorted(Comparator.comparing(DesktopApplication::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public DesktopLaunchTarget resolve(String query) {
        String normalizedQuery = normalizeQuery(query);
        List<DesktopLaunchTarget> targets = loadTargets();
        Optional<DesktopLaunchTarget> exactMatch = targets.stream()
                .filter(target -> aliases(target).contains(normalizedQuery))
                .findFirst();
        if (exactMatch.isPresent()) {
            return exactMatch.get();
        }
        return targets.stream()
                .filter(target -> aliases(target).stream().anyMatch(alias -> alias.contains(normalizedQuery)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown application: " + query));
    }

    private List<DesktopLaunchTarget> loadTargets() {
        Map<String, DesktopLaunchTarget> applications = new LinkedHashMap<>();
        for (String dir : properties.getApplicationDirs()) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path applicationDir = Path.of(dir);
            if (!Files.isDirectory(applicationDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(applicationDir)) {
                for (Path desktopFile : stream
                        .filter(path -> path.getFileName().toString().endsWith(".desktop"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList()) {
                    parseDesktopFile(desktopFile)
                            .ifPresent(target -> applications.put(target.application().desktopId(), target));
                }
            } catch (IOException ignored) {
                // Directory scan is best-effort; unavailable paths should not block the service.
            }
        }
        return List.copyOf(applications.values());
    }

    private Optional<DesktopLaunchTarget> parseDesktopFile(Path desktopFile) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean inDesktopEntry = false;
        try {
            for (String rawLine : Files.readAllLines(desktopFile)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inDesktopEntry = "[Desktop Entry]".equals(line);
                    continue;
                }
                if (!inDesktopEntry) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                values.putIfAbsent(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        if (!"Application".equalsIgnoreCase(values.getOrDefault("Type", "Application"))) {
            return Optional.empty();
        }
        if (Boolean.parseBoolean(values.getOrDefault("Hidden", "false"))
                || Boolean.parseBoolean(values.getOrDefault("NoDisplay", "false"))) {
            return Optional.empty();
        }

        List<String> command = parseExec(values.get("Exec"));
        if (command.isEmpty()) {
            return Optional.empty();
        }

        String desktopId = desktopFile.getFileName().toString();
        String name = firstNonBlank(values.get("Name"), stripDesktopSuffix(desktopId));
        List<String> categories = splitList(values.get("Categories"));
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(name);
        aliases.add(stripDesktopSuffix(desktopId));
        String executableAlias = executableAlias(command);
        if (executableAlias != null) {
            aliases.add(executableAlias);
        }
        String genericName = values.get("GenericName");
        if (genericName != null && !genericName.isBlank()) {
            aliases.add(genericName.trim());
        }

        DesktopApplication application = new DesktopApplication(
                desktopId,
                name,
                aliases.stream().filter(alias -> alias != null && !alias.isBlank()).toList(),
                categories,
                Boolean.parseBoolean(values.getOrDefault("Terminal", "false")),
                desktopFile.toString());
        return Optional.of(new DesktopLaunchTarget(application, command));
    }

    private static List<String> parseExec(String exec) {
        if (exec == null || exec.isBlank()) {
            return List.of();
        }
        List<String> tokens = tokenize(exec);
        List<String> command = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String sanitized = EXEC_FIELD_CODES.matcher(token.replace("%%", "%")).replaceAll("").trim();
            if (!sanitized.isBlank()) {
                command.add(sanitized);
            }
        }
        return List.copyOf(command);
    }

    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;

        for (char character : value.toCharArray()) {
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\' && !singleQuoted) {
                escaped = true;
                continue;
            }
            if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (Character.isWhitespace(character) && !singleQuoted && !doubleQuoted) {
                appendToken(tokens, current);
                continue;
            }
            current.append(character);
        }
        if (escaped) {
            current.append('\\');
        }
        appendToken(tokens, current);
        return tokens;
    }

    private static void appendToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static List<String> splitList(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        return Stream.of(rawValue.split(";"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String stripDesktopSuffix(String desktopId) {
        if (desktopId.endsWith(".desktop")) {
            return desktopId.substring(0, desktopId.length() - ".desktop".length());
        }
        return desktopId;
    }

    private static String executableAlias(List<String> command) {
        if (command.isEmpty()) {
            return null;
        }
        int executableIndex = 0;
        if ("env".equals(command.getFirst()) && command.size() > 1) {
            executableIndex = 1;
            while (executableIndex < command.size() && command.get(executableIndex).contains("=")) {
                executableIndex++;
            }
        }
        if (executableIndex >= command.size()) {
            return null;
        }
        String executable = command.get(executableIndex);
        int lastSlash = executable.lastIndexOf('/');
        return lastSlash >= 0 ? executable.substring(lastSlash + 1) : executable;
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Application name is required");
        }
        String normalized = query.trim();
        if (!SAFE_QUERY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unsafe application name: " + query);
        }
        return normalize(normalized);
    }

    private static Set<String> aliases(DesktopLaunchTarget target) {
        return target.application().aliases().stream()
                .map(DesktopEntryApplicationCatalog::normalize)
                .collect(Collectors.toSet());
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }
}

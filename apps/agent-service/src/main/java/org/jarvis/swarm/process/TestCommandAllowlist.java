package org.jarvis.swarm.process;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict allowlist for the TESTER role's real test-runner invocations. Beyond checking
 * the binary name, this validates the FULL argument shape for every real test runner
 * TESTER may execute, so a crafted goal cannot smuggle extra flags (e.g.
 * {@code -Dexec.executable=...}) or path traversal (e.g. {@code -pl ../../secrets}) past
 * a plain binary-name check. A handful of tiny, inherently side-effect-free commands
 * (echo/true/false/ls/cat/printf/pwd) are allowed with any arguments, unchanged from the
 * original MVP allowlist.
 */
public final class TestCommandAllowlist {

    private static final Set<String> SIMPLE_SAFE = Set.of("echo", "true", "false", "ls", "cat", "printf", "pwd");
    private static final Pattern SAFE_RELATIVE_PATH = Pattern.compile("^[A-Za-z0-9._/-]+$");

    private TestCommandAllowlist() {
    }

    public static boolean isAllowed(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String binary = baseName(command.get(0));
        List<String> args = command.subList(1, command.size());
        if (SIMPLE_SAFE.contains(binary)) {
            return true;
        }
        return switch (binary) {
            case "mvn", "mvnw" -> isAllowedMaven(args);
            case "gradle", "gradlew" -> args.equals(List.of("test"));
            case "npm" -> args.equals(List.of("test")) || args.equals(List.of("run", "test"));
            case "pytest" -> isAllowedPytest(args);
            default -> false;
        };
    }

    /** {@code mvn [-q] [-pl <safe-module-path>] test} — nothing else. */
    private static boolean isAllowedMaven(List<String> args) {
        if (args.isEmpty() || !"test".equals(args.get(args.size() - 1))) {
            return false;
        }
        List<String> flags = args.subList(0, args.size() - 1);
        int i = 0;
        while (i < flags.size()) {
            String flag = flags.get(i);
            if ("-q".equals(flag)) {
                i++;
                continue;
            }
            if ("-pl".equals(flag)) {
                if (i + 1 >= flags.size() || !isSafeRelativePath(flags.get(i + 1))) {
                    return false;
                }
                i += 2;
                continue;
            }
            return false;
        }
        return true;
    }

    /** {@code pytest} with zero args, or exactly one safe relative path argument. */
    private static boolean isAllowedPytest(List<String> args) {
        if (args.isEmpty()) {
            return true;
        }
        if (args.size() > 1) {
            return false;
        }
        String path = args.get(0);
        return !path.startsWith("-") && isSafeRelativePath(path);
    }

    private static boolean isSafeRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
            return false;
        }
        return SAFE_RELATIVE_PATH.matcher(path).matches();
    }

    private static String baseName(String binary) {
        int slash = binary.lastIndexOf('/');
        return slash >= 0 ? binary.substring(slash + 1) : binary;
    }
}

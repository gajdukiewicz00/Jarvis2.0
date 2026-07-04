package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.model.AskScreenResult;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.jarvis.visionsecurity.service.cv.AskScreenCvService;
import org.jarvis.visionsecurity.service.cv.ScreenContextCvService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Headless CLI entry point. Activate with one of:
 * <pre>
 *   --cv.input=/path/to/image.png         # OCR a file
 *   --cv.screenshot=true                  # capture screen, then OCR
 *   --cv.screen-context=true              # screenshot + OCR + window + tags + detectors
 *   --cv.ask-screen="What is on my screen?"  # screen-context + local VLM answer
 *   --cv.output=/path/to/out.png          # (optional) screenshot target
 *   --cv.user=owner                       # (optional) user id for the capture
 * </pre>
 * Prints structured JSON to stdout and exits. Exit codes:
 * <ul>
 *   <li>{@code 0} success</li>
 *   <li>{@code 2} OCR engine unavailable</li>
 *   <li>{@code 3} input file missing</li>
 *   <li>{@code 4} any other CV failure</li>
 *   <li>{@code 5} (ask-screen only) the local VLM was not configured or
 *       unavailable, so no answer was produced — the screen context is still
 *       printed</li>
 * </ul>
 * Intended for ops / smoke tests and component integration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CvCliRunner implements ApplicationRunner {

    private final LocalCvService cvService;
    private final ScreenContextCvService screenContextCvService;
    private final AskScreenCvService askScreenCvService;
    private final ApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        List<String> askArgs = args.getOptionValues("cv.ask-screen");
        boolean wantAsk = askArgs != null && !askArgs.isEmpty();
        boolean wantScreenContext = parseBool(args.getOptionValues("cv.screen-context"));
        boolean wantScreenshot = parseBool(args.getOptionValues("cv.screenshot"));
        List<String> inputArgs = args.getOptionValues("cv.input");
        if (!wantAsk && !wantScreenContext && !wantScreenshot
                && (inputArgs == null || inputArgs.isEmpty())) {
            return; // CLI mode not requested; let the web server keep running
        }

        Object payload;
        boolean success;
        String error;
        String vlmAvailability = null;
        if (wantAsk) {
            List<String> targetArgs = args.getOptionValues("cv.output");
            Path target = targetArgs != null && !targetArgs.isEmpty()
                    ? Path.of(targetArgs.get(0))
                    : null;
            List<String> userArgs = args.getOptionValues("cv.user");
            String userId = userArgs != null && !userArgs.isEmpty()
                    ? userArgs.get(0) : "cli";
            AskScreenResult result = askScreenCvService.ask(userId, askArgs.get(0), target);
            payload = result;
            success = result.success();
            error = result.error();
            vlmAvailability = result.vlm() == null ? null : result.vlm().availability();
        } else if (wantScreenContext) {
            List<String> targetArgs = args.getOptionValues("cv.output");
            Path target = targetArgs != null && !targetArgs.isEmpty()
                    ? Path.of(targetArgs.get(0))
                    : null;
            List<String> userArgs = args.getOptionValues("cv.user");
            String userId = userArgs != null && !userArgs.isEmpty()
                    ? userArgs.get(0) : "cli";
            ScreenContextResult result = screenContextCvService.capture(userId, target);
            payload = result;
            success = result.success();
            error = result.error();
        } else if (wantScreenshot) {
            List<String> targetArgs = args.getOptionValues("cv.output");
            Path target = targetArgs != null && !targetArgs.isEmpty()
                    ? Path.of(targetArgs.get(0))
                    : null;
            CvAnalysisResult result = cvService.analyzeScreenshot(target);
            payload = result;
            success = result.success();
            error = result.error();
        } else {
            Path imagePath = Path.of(inputArgs.get(0));
            CvAnalysisResult result = cvService.analyzeFile(imagePath);
            payload = result;
            success = result.success();
            error = result.error();
        }

        String json;
        try {
            json = mapper().writeValueAsString(payload);
        } catch (Exception ex) {
            log.error("Failed to serialise CV result", ex);
            System.out.println("{\"success\":false,\"error\":\"serialisation_failed\"}");
            exit(4);
            return;
        }
        System.out.println(json);

        int code = exitCodeFor(success, error, wantAsk, vlmAvailability);
        exit(code);
    }

    static int exitCodeFor(boolean success, String error, boolean askScreen, String vlmAvailability) {
        if (!success) {
            String msg = error == null ? "" : error;
            if (msg.startsWith("OCR engine unavailable")) return 2;
            if (msg.startsWith("Image file not found")) return 3;
            return 4;
        }
        // ask-screen succeeded at the screen-context level; if the VLM did not
        // actually answer (not configured / unreachable), surface code 5 so a
        // caller can tell "no answer" apart from "answered".
        if (askScreen && vlmAvailability != null && !"READY".equals(vlmAvailability)) {
            return 5;
        }
        return 0;
    }

    private void exit(int code) {
        SpringApplication.exit(context, () -> code);
        System.exit(code);
    }

    private static boolean parseBool(List<String> values) {
        if (values == null || values.isEmpty()) return false;
        String v = values.get(0);
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

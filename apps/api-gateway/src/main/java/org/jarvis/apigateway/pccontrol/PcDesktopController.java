package org.jarvis.apigateway.pccontrol;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.capability.GatewayCapabilityService;
import org.jarvis.apigateway.client.PcControlClient;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Official real PC-control executor route (Roadmap #1.8/#8).
 *
 * <p>Distinct from the generic {@code /api/v1/pc/**} passthrough
 * ({@link org.jarvis.apigateway.controller.PcControlProxyController}), this
 * controller is the single hardened entry point for desktop actions:
 * open app/url, focus window, screenshot, type text, hotkey. It enforces its
 * own narrow allowlist server-side (see {@link DesktopActionPolicy}) —
 * refusing anything dangerous or unrecognized, and requiring
 * {@code confirm=true} for guarded actions — before forwarding to pc-control
 * via the existing {@link PcControlClient}, which reaches either the
 * in-cluster (stub or non-stub) pc-control Service or, once
 * {@code PC_CONTROL_URL} is repointed at the selectorless {@code host-pc-control}
 * Service, the real pc-control process running on the workstation host
 * (see scripts/jarvis-pc-control-up.sh, infra/k8s/overlays/prod/host-pc-control.yaml).</p>
 *
 * <p>Every call — forwarded, guarded, or refused — is audited via the shared
 * {@link AuditPublisher} (same Kafka audit trail as {@link org.jarvis.apigateway.audit.AuditIngestController}).</p>
 *
 * <p>Requests reach this controller only after passing {@code JwtAuthFilter}
 * (see {@link org.jarvis.apigateway.security.SecurityConfig}: {@code anyRequest().authenticated()}
 * with no permit-all override for this path) — JWT auth is inherited automatically.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pc/desktop")
public class PcDesktopController {

    private final PcControlClient pcControlClient;
    private final GatewayCapabilityService gatewayCapabilityService;
    private final ObjectProvider<AuditPublisher> auditProvider;

    public PcDesktopController(PcControlClient pcControlClient,
                               GatewayCapabilityService gatewayCapabilityService,
                               ObjectProvider<AuditPublisher> auditProvider) {
        this.pcControlClient = pcControlClient;
        this.gatewayCapabilityService = gatewayCapabilityService;
        this.auditProvider = auditProvider;
    }

    /** Real-vs-stub status flag for this executor (see {@link GatewayCapabilityService#describeDesktopControlStatus()}). */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(gatewayCapabilityService.describeDesktopControlStatus());
    }

    @PostMapping("/action")
    public ResponseEntity<Map<String, Object>> executeAction(@RequestBody DesktopActionRequest request) {
        String type = DesktopActionPolicy.normalize(request.type());
        DesktopActionClass classification = DesktopActionPolicy.classify(type);
        String userId = currentUserId();

        if (classification == DesktopActionClass.DANGEROUS || classification == DesktopActionClass.UNKNOWN) {
            log.warn("Refused desktop action type={} classification={} user={}", type, classification, userId);
            audit(AuditEventType.COMMAND_FAILED, userId, type, Map.of(
                    "classification", classification.name(),
                    "refused", true));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(refusalBody(type, classification, "Action is not on the desktop-control allowlist and was refused"));
        }

        if (classification == DesktopActionClass.GUARDED && !request.confirm()) {
            audit(AuditEventType.CONFIRMATION_REQUESTED, userId, type, Map.of(
                    "classification", classification.name()));
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                    .body(refusalBody(type, classification, "Guarded action requires confirm=true"));
        }

        // Only reachable for SAFE actions, or GUARDED actions with confirm=true.
        gatewayCapabilityService.requireDirectPcControlSupport("desktop-action");

        Map<String, Object> downstreamRequest = new LinkedHashMap<>();
        downstreamRequest.put("actionType", type);
        downstreamRequest.put("parameters", request.parameters());

        try {
            ResponseEntity<String> downstreamResponse = pcControlClient.executeAction(downstreamRequest);
            audit(AuditEventType.COMMAND_EXECUTED, userId, type, Map.of(
                    "classification", classification.name(),
                    "upstreamStatus", downstreamResponse.getStatusCode().value()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("classification", classification.name());
            body.put("upstreamStatus", downstreamResponse.getStatusCode().value());
            body.put("upstreamBody", downstreamResponse.getBody());
            return ResponseEntity.status(downstreamResponse.getStatusCode()).body(body);
        } catch (RuntimeException ex) {
            audit(AuditEventType.COMMAND_FAILED, userId, type, Map.of(
                    "classification", classification.name(),
                    "error", String.valueOf(ex.getMessage())));
            throw ex;
        }
    }

    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
    }

    private Map<String, Object> refusalBody(String type, DesktopActionClass classification, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("classification", classification.name());
        body.put("refused", true);
        body.put("message", message);
        return body;
    }

    private void audit(AuditEventType eventType, String userId, String actionType, Map<String, Object> extra) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) {
            log.debug("desktop action audit skipped (no AuditPublisher bean): actionType={}", actionType);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("actionType", actionType);
        publisher.audit(eventType, null, null, userId, null, payload);
    }
}

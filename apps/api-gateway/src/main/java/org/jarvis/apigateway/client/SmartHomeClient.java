package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "smart-home", url = "${services.smart-home.url:http://localhost:8086}")
public interface SmartHomeClient {

    @PostMapping("/api/v1/smarthome/devices/{id}/action")
    ResponseEntity<String> deviceAction(@PathVariable("id") String deviceId,
            @RequestBody Map<String, String> request);
}

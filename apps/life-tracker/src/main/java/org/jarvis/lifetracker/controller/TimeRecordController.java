package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.ActiveTimeRecord;
import org.jarvis.lifetracker.domain.TimeRecord;
import org.jarvis.lifetracker.dto.TimeRecordDTO;
import org.jarvis.lifetracker.repository.ActiveTimeRecordRepository;
import org.jarvis.lifetracker.repository.TimeRecordRepository;
import org.jarvis.lifetracker.service.DTOMapper;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/life/time")
@RequiredArgsConstructor
@Validated
public class TimeRecordController {

    private final TimeRecordRepository timeRecordRepository;
    private final ActiveTimeRecordRepository activeTimeRecordRepository;
    private final DTOMapper dtoMapper;

    @PostMapping("/start")
    public ResponseEntity<TimeRecordDTO> startTimer(
            @Valid @RequestBody TimeRequest request,
            HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        Optional<ActiveTimeRecord> existing = activeTimeRecordRepository.findByUserId(userId);
        if (existing.isPresent()) {
            ActiveTimeRecord active = existing.get();
            if (active.getActivity().equals(request.activity())
                    && active.getCategory().equals(request.category())) {
                return ResponseEntity.ok(toDto(active));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Active timer already running. Stop it before starting a new one.");
        }

        log.info("Starting timer for userId={} activity={}", userId, request.activity());
        ActiveTimeRecord record = new ActiveTimeRecord();
        record.setUserId(userId);
        record.setActivity(request.activity());
        record.setCategory(request.category());
        record.setStartTime(LocalDateTime.now());
        ActiveTimeRecord saved = activeTimeRecordRepository.save(record);
        return ResponseEntity.ok(toDto(saved));
    }

    @PostMapping("/stop")
    @Transactional
    public ResponseEntity<TimeRecordDTO> stopTimer(HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        Optional<ActiveTimeRecord> existing = activeTimeRecordRepository.findByUserId(userId);
        if (existing.isEmpty()) {
            return timeRecordRepository.findTopByUserIdOrderByEndTimeDesc(userId)
                    .map(dtoMapper::toDTO)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        }

        ActiveTimeRecord active = existing.get();
        log.info("Stopping timer for userId={} activity={}", userId, active.getActivity());
        LocalDateTime endTime = LocalDateTime.now();
        TimeRecord record = new TimeRecord();
        record.setUserId(userId);
        record.setActivity(active.getActivity());
        record.setCategory(active.getCategory());
        record.setStartTime(active.getStartTime());
        record.setEndTime(endTime);
        record.setDurationSeconds(Duration.between(active.getStartTime(), endTime).getSeconds());
        TimeRecord saved = timeRecordRepository.save(record);
        activeTimeRecordRepository.deleteById(active.getId());
        return ResponseEntity.ok(dtoMapper.toDTO(saved));
    }

    @GetMapping("/records")
    public ResponseEntity<List<TimeRecordDTO>> getRecords(HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        List<TimeRecordDTO> records = timeRecordRepository.findByUserIdOrderByStartTimeDesc(userId).stream()
                .map(dtoMapper::toDTO)
                .toList();
        return ResponseEntity.ok(records);
    }

    private TimeRecordDTO toDto(ActiveTimeRecord record) {
        return new TimeRecordDTO(
                record.getId(),
                record.getActivity(),
                record.getCategory(),
                record.getStartTime(),
                null,
                null);
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }

    public record TimeRequest(
            @NotBlank String activity,
            @NotBlank String category) {
    }
}

package org.jarvis.lifetracker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.TimeRecord;
import org.jarvis.lifetracker.repository.TimeRecordRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/life/time")
@RequiredArgsConstructor
public class TimeRecordController {

    private final TimeRecordRepository timeRecordRepository;
    private final org.jarvis.lifetracker.service.DTOMapper dtoMapper;
    private TimeRecord activeRecord;

    @PostMapping("/start")
    public org.jarvis.lifetracker.dto.TimeRecordDTO startTimer(@RequestBody TimeRequest request) {
        if (activeRecord != null) {
            stopTimer();
        }
        log.info("Starting timer for: {}", request.activity());
        TimeRecord record = new TimeRecord();
        record.setActivity(request.activity());
        record.setCategory(request.category());
        record.setStartTime(LocalDateTime.now());
        activeRecord = record;
        return dtoMapper.toDTO(record);
    }

    @PostMapping("/stop")
    public org.jarvis.lifetracker.dto.TimeRecordDTO stopTimer() {
        if (activeRecord == null) {
            throw new IllegalStateException("No active timer");
        }
        log.info("Stopping timer for: {}", activeRecord.getActivity());
        activeRecord.setEndTime(LocalDateTime.now());
        activeRecord.setDurationSeconds(
                Duration.between(activeRecord.getStartTime(), activeRecord.getEndTime()).getSeconds());
        TimeRecord saved = timeRecordRepository.save(activeRecord);
        activeRecord = null;
        return dtoMapper.toDTO(saved);
    }

    @GetMapping("/records")
    public List<org.jarvis.lifetracker.dto.TimeRecordDTO> getRecords() {
        return timeRecordRepository.findAll().stream()
                .map(dtoMapper::toDTO)
                .toList();
    }

    public record TimeRequest(String activity, String category) {
    }
}

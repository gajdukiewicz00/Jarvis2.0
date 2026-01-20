package org.jarvis.lifetracker.tooling.dto;

import lombok.Data;
import org.jarvis.lifetracker.domain.TransactionType;

import java.time.LocalDateTime;

@Data
public class ListTransactionsToolRequest extends StrictToolRequest {
    private LocalDateTime from;
    private LocalDateTime to;
    private String category;
    private TransactionType type;
}

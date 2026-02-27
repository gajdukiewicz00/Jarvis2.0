package org.jarvis.pccontrol.service;

/**
 * Raised when timer capacity is exhausted.
 */
public class TimerLimitExceededException extends RuntimeException {

    public TimerLimitExceededException(String message) {
        super(message);
    }
}

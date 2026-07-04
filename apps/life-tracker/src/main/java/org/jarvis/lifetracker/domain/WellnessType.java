package org.jarvis.lifetracker.domain;

/** Kinds of wellness data the user can log. */
public enum WellnessType {
    HABIT,    // a habit check-in (numericValue = 1 done, 0 skipped; textValue = habit name)
    WEIGHT,   // body weight (numericValue = kg)
    MOOD,     // mood rating (numericValue = 1..5; textValue = note)
    STEPS,    // step count (numericValue = steps)
    SLEEP,    // sleep duration (numericValue = hours)
    WORKOUT,  // workout (numericValue = minutes; textValue = activity)
    NOTE      // free-form note (textValue)
}

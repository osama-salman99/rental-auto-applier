package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The listing's true availability, separate from the coarser {@code stage}. A property can read
 * {@code stage="option"} (someone holds an option) while its {@code status.code="available"} —
 * i.e. it is still open for (backup) applications, and a human on roofz.eu sees it as "Beschikbaar".
 * Keying availability on this, not on {@code stage}, is why we no longer miss those.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiStatus(String code, String label) {}

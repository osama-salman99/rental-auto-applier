package eu.rebo.crawler.service;

/**
 * The applicant + object fields posted to REBO's "property_to_market" Interesse form. The three
 * consent/registration toggles are always affirmative — an Interesse registration is only meaningful
 * with them set, and the form rejects it (422) otherwise.
 */
public record ApplyPayload(
        String firstName,
        String lastName,
        String email,
        String phone,
        String incomeType,
        String comment,
        String objectId,
        String objectAddress,
        String objectPostal) {}

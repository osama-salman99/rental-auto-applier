package eu.roofz.crawler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class TwilioCallService {

    private static final Logger log = LoggerFactory.getLogger(TwilioCallService.class);

    private final RestClient restClient;

    @Value("${twilio.account-sid:}") private String accountSid;
    @Value("${twilio.auth-token:}") private String authToken;
    @Value("${twilio.flow-sid:}") private String flowSid;
    @Value("${twilio.from-number:}") private String fromNumber;
    @Value("${twilio.to-number:}") private String toNumber;

    public TwilioCallService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void triggerNewListingCall() {
        if (accountSid.isBlank() || authToken.isBlank() || flowSid.isBlank()) {
            log.warn("Twilio not configured, skipping call");
            return;
        }
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("To", toNumber);
            body.add("From", fromNumber);

            restClient.post()
                    .uri("https://studio.twilio.com/v2/Flows/{flowSid}/Executions", flowSid)
                    .headers(h -> h.setBasicAuth(accountSid, authToken))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Twilio call triggered for new listing");
        } catch (Exception e) {
            log.error("Failed to trigger Twilio call", e);
        }
    }
}

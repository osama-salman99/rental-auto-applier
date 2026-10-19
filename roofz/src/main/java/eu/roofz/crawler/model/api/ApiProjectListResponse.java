package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiProjectListResponse(List<ApiProject> data) {}

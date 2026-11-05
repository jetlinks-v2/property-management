package org.jetlinks.pro.smartpark.handover.api;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** Persisted export selector; authorization is resolved again by the worker and download. */
@Getter
@Setter
public class ExportRequest {
    private String projectId;
    private String kind;
    private String source;
    private String stage;
    private List<String> ids;
    private Map<String, Object> filters;
}

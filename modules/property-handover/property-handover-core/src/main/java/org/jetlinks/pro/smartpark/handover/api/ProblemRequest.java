package org.jetlinks.pro.smartpark.handover.api;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Problem intake contract shared by PC, H5, and the report-to-inspection command. */
@Getter
@Setter
public class ProblemRequest {
    private Integer version;
    private Integer status;
    private String projectId;
    private String source;
    private String itemId;
    private String clientRequestId;
    private String title;
    private String description;
    private String location;
    private String spaceId;
    private String categoryId;
    private String organizationId;
    private String inspectionType;
    private Long inspectionTime;
    private String initiatorId;
    private String sourceReportId;
    private List<FileRef> photos = List.of();
}

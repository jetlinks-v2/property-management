package org.jetlinks.pro.smartpark.handover.api;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Every mutating action is conditional on the version observed by the caller. */
@Getter
@Setter
public class ActionRequest {
    private Integer version;
    private String organizationId;
    private String description;
    private Boolean passed;
    private String reason;
    private List<FileRef> photos = List.of();
}

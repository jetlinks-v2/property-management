package org.jetlinks.pro.smartpark.handover.api;

import lombok.Getter;
import lombok.Setter;

/** Stable platform file identity; temporary preview URLs are never persisted. */
@Getter
@Setter
public class FileRef {
    private String fileId;
    private String name;
    private String type;
    private Long size;
    private String photoType;
    private Integer sortIndex;
}

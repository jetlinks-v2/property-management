package org.jetlinks.pro.smartpark.handover.api;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BatchReviewRequest {
    private String projectId;
    private List<Item> items;
    private Boolean passed;
    private String reason;

    @Getter
    @Setter
    public static class Item {
        private String id;
        private Integer version;
    }
}

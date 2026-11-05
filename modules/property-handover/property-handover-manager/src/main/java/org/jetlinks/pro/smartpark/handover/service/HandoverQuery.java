package org.jetlinks.pro.smartpark.handover.service;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;

import java.util.List;

/** One selector for list, counts and export; all filtering happens before database paging. */
@Getter
@Setter
public class HandoverQuery {
    private String projectId;
    private Boolean enabled;
    private String operator;
    private Long modifyTimeFrom;
    private Long modifyTimeTo;
    private int pageIndex = 0;
    private int pageSize = 12;
    private String keyword;
    private String location;
    private String source;
    private Integer status;
    private Integer auditStage;
    private String organizationId;
    private String categoryId;
    private String itemId;
    private String stage;
    private String view;
    private Long inspectionTimeFrom;
    private Long inspectionTimeTo;
    private List<String> ids;

    public QueryParamEntity parameter(String keywordField) {
        QueryParamEntity q = new QueryParamEntity();
        q.setPageIndex(Math.max(0, pageIndex));
        q.setPageSize(Math.max(1, Math.min(200, pageSize)));
        q.toQuery().orderByDesc("createTime").orderByAsc("id");
        if (HandoverRules.hasText(keyword)) q.toQuery().like(keywordField, "%" + keyword + "%");
        if (HandoverRules.hasText(source)) q.toQuery().and("source", source);
        if (status != null) q.toQuery().and("status", status);
        if (auditStage != null) q.toQuery().and("auditStage", auditStage);
        if (HandoverRules.hasText(organizationId))
            q.toQuery().and("organizationId", organizationId);
        if (HandoverRules.hasText(categoryId)) q.toQuery().and("categoryId", categoryId);
        if (HandoverRules.hasText(itemId)) q.toQuery().and("itemId", itemId);
        if (HandoverRules.hasText(stage)) q.toQuery().and("stage", stage);
        if (inspectionTimeFrom != null)
            q.toQuery().and("inspectionTime", "gte", inspectionTimeFrom);
        if (inspectionTimeTo != null) q.toQuery().and("inspectionTime", "lte", inspectionTimeTo);
        if (HandoverRules.hasText(location)) q.toQuery().like("location", "%" + location + "%");
        if (modifyTimeFrom != null) q.toQuery().and("modifyTime", "gte", modifyTimeFrom);
        if (modifyTimeTo != null) q.toQuery().and("modifyTime", "lte", modifyTimeTo);
        if (ids != null && !ids.isEmpty()) q.toQuery().in("id", ids);
        return q;
    }
}

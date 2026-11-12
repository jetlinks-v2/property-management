package org.jetlinks.pro.property.decoration.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 在线审核只读取表格字段；签署正文、附件、批注和备案历史由详情入口按需读取。 */
@Service
@RequiredArgsConstructor
public class DecorationReviewListService {
    private final DecorationScope scope;
    private final QueryHelper queries;

    // 审核口径与详情一致：自检归入资料，驳回优先，空选填项不占待审；不返回材料快照。
    private static final String REVIEW_STATE = """
        (select case
          when count(case when (m->>'reviewStatus')::integer=2 then 1 end)>0 then 'rejected'
          when count(case when (m->'definition'->>'required')::boolean
            or jsonb_array_length(coalesce(m->'fileIds','[]'::jsonb))>0 then 1 end)=0 then 'notRequired'
          when count(case when coalesce((m->>'reviewStatus')::integer,0)<>1
            and ((m->'definition'->>'required')::boolean
              or jsonb_array_length(coalesce(m->'fileIds','[]'::jsonb))>0) then 1 end)=0 then 'approved'
          else 'pending' end
        from jsonb_array_elements(coalesce(a.details::jsonb->'materials','[]'::jsonb)
          || case when a.details::jsonb->'selfInspectionReview' is not null
                    and a.details::jsonb->'selfInspectionReview'<>'null'::jsonb
             then jsonb_build_array(a.details::jsonb->'selfInspectionReview') else '[]'::jsonb end) m
        where m->'definition'->>'kind'='%s')
        """;
    private static final String SELECT = """
        select a.id,a.project_id,a.number,a.project_name,a.space_name,a.owner_name,a.contact_phone,
          a.construction_unit,a.construction_manager,a.manager_phone,a.house_type,a.decoration_type,
          a.create_time,a.start_time,a.completion_time,a.status,a.creator_name,a.deposit_review_status,a.refund_status,
          %s as material_review, %s as drawing_review
        from pd_application a
        """.formatted(REVIEW_STATE.formatted("material"), REVIEW_STATE.formatted("drawing"));

    public Mono<PagerResult<Row>> query(QueryParamEntity input) {
        // 沿用申请的应用、租户、项目权限以及服务端分页上限，不信任客户端 includes/excludes。
        return scope.current().flatMap(ctx -> scope.query(input, ctx)).flatMap(query -> {
            query.toNestQuery(nest -> nest.and("deleted", false));
            return queries.select(SELECT, Row::new).where(query)
                .fetchPaged(query.getPageIndex(), query.getPageSize());
        });
    }

    @Getter @Setter
    public static class Row {
        private String id, projectId, number, projectName, spaceName, ownerName, contactPhone;
        private String constructionUnit, constructionManager, managerPhone, houseType, decorationType;
        private Long createTime, startTime, completionTime;
        private String status, creatorName;
        private Integer depositReviewStatus, refundStatus;
        private String materialReview, drawingReview;
    }
}

package org.jetlinks.pro.property.decoration.service;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 每次周期任务一行：待巡检、巡检结果与最新整改状态汇总，详情仍读取独立任务及反馈。 */
@Service
@RequiredArgsConstructor
public class DecorationInspectionListService {
    private final DecorationScope scope;
    private final QueryHelper queries;

    // 沿用本模块 PostgreSQL JSON 查询；仅投影列表字段，不读取检查项、照片和完整整改历史。
    // 重发产生多个反馈时选最新有效反馈，先汇总再筛选分页，避免一条任务被展开为多行。
    private static final String SELECT = """
        select s.* from (
          select t.id,t.decoration_id,t.application_id,t.tenant_id,t.project_id,a.deleted,
            t.number,t.project_name,t.space_name,a.status as decoration_status,
            coalesce(r.assignee_name,t.assignee_name) as assignee_name,
            coalesce(r.status,t.status) as status,
            case when r.id is null then 'pending' when r.status='passed' then 'normal' else 'violation' end as inspection_result,
            t.details::jsonb->>'scheduledDate' as scheduled_date,
            coalesce(t.details::jsonb->'inspectionRoles','[]'::jsonb)::text as inspection_roles_json,
            t.create_time,greatest(t.modify_time,u.latest_update_time) as modify_time
          from pd_task t
          join pd_application a on a.id=t.decoration_id and a.application_id=t.application_id
            and a.tenant_id=t.tenant_id and a.project_id=t.project_id
          left join (
            select f.id,f.parent_id,f.decoration_id,f.application_id,f.tenant_id,f.project_id,
              f.assignee_name,f.status,
              row_number() over (partition by f.application_id,f.tenant_id,f.project_id,f.decoration_id,f.parent_id
                order by case when f.status='reissued' then 1 else 0 end,f.create_time desc,f.id desc) as position
            from pd_task f where f.kind='inspectionRecord'
          ) r on r.parent_id=t.id and r.decoration_id=t.decoration_id and r.application_id=t.application_id
            and r.tenant_id=t.tenant_id and r.project_id=t.project_id and r.position=1
          left join (
            select application_id,tenant_id,project_id,decoration_id,parent_id,max(modify_time) as latest_update_time
            from pd_task where kind='inspectionRecord'
            group by application_id,tenant_id,project_id,decoration_id,parent_id
          ) u on u.parent_id=t.id and u.decoration_id=t.decoration_id and u.application_id=t.application_id
            and u.tenant_id=t.tenant_id and u.project_id=t.project_id
          where t.kind='daily'
        ) s
        """;

    /** 权限条件作用于聚合结果；关联表同时限定申请、租户、项目，不能串入别的任务反馈。 */
    public Mono<PagerResult<Row>> query(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scope.query(input, ctx).flatMap(query -> {
            query.toNestQuery(nest -> nest.and("deleted", false));
            return queries.select(SELECT, Row::new).where(query)
                .fetchPaged(query.getPageIndex(), query.getPageSize())
                .doOnNext(page -> {
                    var roles = ctx.authentication().getDimensions("role").stream().map(role -> role.getId()).toList();
                    for (var row : page.getData()) {
                        row.setFeedbackAllowed("pending".equals(row.getStatus()) &&
                            JSON.parseArray(row.getInspectionRolesJson(), DecorationModels.InspectionRole.class)
                                .stream().anyMatch(role -> roles.contains(role.roleId())));
                    }
                });
        }));
    }

    @Getter @Setter
    public static class Row {
        private String id, decorationId, projectId, number, projectName, spaceName, decorationStatus;
        private String assigneeName, status, inspectionResult, scheduledDate;
        private Long createTime, modifyTime;
        private boolean feedbackAllowed;
        @JsonIgnore private String inspectionRolesJson;
    }
}

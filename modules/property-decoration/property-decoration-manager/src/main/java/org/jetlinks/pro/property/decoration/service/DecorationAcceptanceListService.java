package org.jetlinks.pro.property.decoration.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 每套装修、每种验收只返回一条办理记录；人员任务和历次结果仍由详情任务接口读取。 */
@Service
@RequiredArgsConstructor
public class DecorationAcceptanceListService {
    private final DecorationScope scope;
    private final QueryHelper queries;

    public enum Kind {
        firstAcceptance(1), secondAcceptance(2);
        private final int type;
        Kind(int type) { this.type = type; }
    }

    // 待发起与已发起在数据库内合并再分页；不为尚未申请的装修伪造人员任务或申请日期。
    private static String eligible(Kind kind) {
        return "a.actual_end_time is null and a.refund_status<>2 and "
            + "(a.permit::jsonb->>'collectedTime' is not null or a.permit::jsonb->>'printTime' is not null or a.permit::jsonb->>'downloadTime' is not null) and "
            + (kind == Kind.firstAcceptance ? "(a.acceptance_type=0 or a.status='first_acceptance_failed')"
                : "a.status in ('pending_second_acceptance','second_acceptance_failed')");
    }
    // PostgreSQL 与现有业务库一致。先在数据库内汇总再筛选、分页，避免多人员或重发造成重复记录。
    // SQL 模板只接受服务端枚举常量；用户条件统一由 QueryHelper 绑定。
    private static final String SELECT = """
        select s.* from (
          select a.id,a.id as decoration_id,a.application_id,a.tenant_id,a.project_id,a.deleted,
            a.status as decoration_status,a.number,a.project_name,a.space_name,a.owner_name,a.contact_phone,a.construction_unit,
            a.construction_manager,a.manager_phone,a.house_type,a.decoration_type,
            '%2$s' as kind,
            case when a.acceptance_type=%1$d then a.acceptance_round else r.round end as round,
            (%3$s and a.source='pc_admin') as request_allowed,
            case when r.round is null and a.acceptance_type<>%1$d then 'not_requested'
              when a.acceptance_type=%1$d and a.acceptance_round>coalesce(r.round,0) then 'waiting_assignment'
              when t.pending_count>0 then 'pending' when t.failed_count>0 then 'failed'
              else 'passed' end as status,
            case when a.acceptance_type=%1$d then a.acceptance_date else t.acceptance_date end as acceptance_date,
            case when a.acceptance_type=%1$d then a.acceptance_request_time else t.acceptance_request_time end as acceptance_request_time,
            case when a.acceptance_type=%1$d then a.acceptance_requester_name else t.acceptance_requester_name end as acceptance_requester_name,
            case when a.acceptance_type=%1$d then a.acceptance_source else t.acceptance_source end as acceptance_source,
            case when a.acceptance_type=%1$d and a.acceptance_round>coalesce(r.round,0) then null else t.assignee_name end as assignee_name,
            case when a.acceptance_type=%1$d or r.round is not null then coalesce(r.create_time,a.acceptance_request_time) end as create_time,
            case when a.acceptance_type=%1$d then a.modify_time else coalesce(t.modify_time,a.modify_time) end as modify_time
          from pd_application a
          left join (
            select decoration_id, max(round) as round,
              min(coalesce(acceptance_request_time,create_time)) as create_time
            from pd_task where kind='%2$s' group by decoration_id
          ) r on r.decoration_id=a.id
          left join (
            select decoration_id,round,
              count(case when status='pending' then 1 end) as pending_count,
              count(case when status='failed' then 1 end) as failed_count,
              string_agg(coalesce(assignee_name,assignee_id),'、' order by assignee_id) as assignee_name,
              max(acceptance_date) as acceptance_date,max(acceptance_request_time) as acceptance_request_time,
              max(acceptance_requester_name) as acceptance_requester_name,max(acceptance_source) as acceptance_source,
              max(modify_time) as modify_time
            from pd_task where kind='%2$s' group by decoration_id,round
          ) t on t.decoration_id=a.id and t.round=r.round
          where a.acceptance_type=%1$d or r.round is not null or (%4$s)
        ) s
        """;

    public Mono<PagerResult<Row>> query(Kind kind, QueryParamEntity input) {
        return query(kind, input, false);
    }

    /** 合并办理列表只额外纳入已完成前置流程的装修，不提前开放下一阶段。 */
    public Mono<PagerResult<Row>> query(Kind kind, QueryParamEntity input, boolean includeEligible) {
        // 继承申请查询的可信应用、租户及基础服务 AssetsHolder 已授权项目范围。
        return scope.current().flatMap(ctx -> scope.query(input, ctx)).flatMap(query -> {
            query.toNestQuery(nest -> nest.and("deleted", false));
            return queries.select(SELECT.formatted(kind.type, kind.name(), eligible(kind), includeEligible ? eligible(kind) : "false"), Row::new).where(query)
                .fetchPaged(query.getPageIndex(), query.getPageSize());
        });
    }

    @Getter @Setter
    public static class Row {
        private String id, decorationId, projectId, kind, number, projectName, spaceName, ownerName, contactPhone;
        private String constructionUnit, constructionManager, managerPhone, houseType, decorationType;
        private Integer round;
        private Boolean requestAllowed;
        private String decorationStatus, status, assigneeName, acceptanceRequesterName, acceptanceSource;
        private Long acceptanceDate, acceptanceRequestTime, createTime, modifyTime;
    }
}

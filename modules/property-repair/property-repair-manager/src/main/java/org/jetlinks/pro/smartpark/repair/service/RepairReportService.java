package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

/** 复用工单控制器注入后的条件；聚合不接触未经授权的工单，也不把全量工单加载到内存。 */
@Service
public class RepairReportService {
    private final QueryHelper helper;
    private final String summarySql;
    public RepairReportService(QueryHelper helper) throws IOException {
        this.helper=helper;
        summarySql=new ClassPathResource("reports/repair-summary.sql").getContentAsString(StandardCharsets.UTF_8);
    }
    public Flux<RepairReportRow> summary(QueryParamEntity scoped){
        scoped.setPaging(false);
        scoped.setSorts(java.util.List.of());
        return helper.select(summarySql,RepairReportRow::new,overdueBoundaries(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            .where(scoped).fetch();
    }
    /** 列表页签只需要状态数量，避免分页时计算费用、满意度和处理过程。 */
    public Flux<RepairReportRow> statusCounts(QueryParamEntity scoped){
        scoped.setPaging(false);scoped.setSorts(java.util.List.of());
        String sql="select count(*) as total, "
            +"sum(case when status=0 then 1 else 0 end) as \"noDistributed\", "
            +"sum(case when status=6 then 1 else 0 end) as \"waitAccept\", "
            +"sum(case when status=7 then 1 else 0 end) as \"waitFollow\", "
            +"sum(case when status=1 then 1 else 0 end) as handling, "
            +"sum(case when status=2 then 1 else 0 end) as finished, "
            +"sum(case when status=8 then 1 else 0 end) as \"waitCallback\", "
            +"sum(case when status=9 then 1 else 0 end) as \"waitAudit\", "
            +"sum(case when status=5 then 1 else 0 end) as archived, "
            +"sum(case when status=3 then 1 else 0 end) as abandoned from sp_repair_order";
        return helper.select(sql,RepairReportRow::new).where(scoped).fetch();
    }
    public Flux<RepairReportExportRow> exportRows(QueryParamEntity scoped){
        scoped.setPaging(false);scoped.setSorts(java.util.List.of());
        // 同次聚合携带每组源工单ID，下载校验不会漏掉两次查询之间的新增或删除。
        String sql=summarySql.replace("/* export-order-ids */","json_agg(id)::text as \"orderIdsJson\",");
        return helper.select(sql,RepairReportExportRow::new,overdueBoundaries(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            .where(scoped).fetch();
    }
    public reactor.core.publisher.Mono<RepairDashboard> dashboard(QueryParamEntity scoped,String interval){
        RepairFlowRules.require(java.util.Set.of("day","month").contains(interval),"dashboard_interval_invalid");
        scoped.setPaging(false);scoped.setSorts(java.util.List.of());
        String format="month".equals(interval)?"YYYY-MM":"YYYY-MM-DD";
        String bucket="to_char(to_timestamp(create_time / 1000.0) at time zone 'Asia/Shanghai', '"+format+"')";
        String trend="select "+bucket+" as name, count(*) as total, "
            +"sum(case when status=0 then 1 else 0 end) as \"noDistributed\", "
            +"sum(case when status=1 then 1 else 0 end) as handling, "
            +"sum(case when status=3 then 1 else 0 end) as abandoned "
            +"from sp_repair_order group by "+bucket+" order by name";
        String urgency="select coalesce(urgency,0)::text as name,count(*) as total from sp_repair_order group by urgency order by urgency";
        // 旧图按首级分类聚合；不携带旧系统的分类ID常量。
        // 分类层级一次关联，避免每张工单重复执行分类子查询；缺失分类仍使用工单名称快照。
        String root="coalesce(root.name,sp_repair_order.category_name,'未分类')";
        String category="select "+root+" as name,count(*) as total from sp_repair_order "
            +"left join sp_repair_configuration leaf on leaf.id=sp_repair_order.category_id "
            +"and leaf.application_id=sp_repair_order.application_id "
            +"left join sp_repair_configuration parent on parent.id=leaf.parent_id "
            +"left join sp_repair_configuration root on root.id=parent.parent_id "
            +"group by "+root+" order by total desc,name";
        return reactor.core.publisher.Mono.zip(points(trend,scoped),points(urgency,scoped),points(category,scoped))
            .map(parts->new RepairDashboard(parts.getT1(),parts.getT2(),parts.getT3()));
    }
    private reactor.core.publisher.Mono<java.util.List<RepairDashboardPoint>> points(String sql,QueryParamEntity scoped){
        return helper.select(sql,RepairDashboardPoint::new).where(scoped.clone()).fetch().collectList();
    }
    /** 原超月报表SQL使用30-59、60-89、90-119、120-365、465天以上，保留其区间。 */
    static Object[] overdueBoundaries(LocalDate day){
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        int[] days={59,29,89,59,119,89,365,119,464};
        return java.util.Arrays.stream(days).mapToObj(n->day.minusDays(n).atStartOfDay(zone).toInstant().toEpochMilli()).toArray();
    }
}

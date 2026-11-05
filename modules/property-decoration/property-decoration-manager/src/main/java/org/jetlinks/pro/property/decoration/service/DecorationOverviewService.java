package org.jetlinks.pro.property.decoration.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;

/** 旧装修首页口径；授权先于数据库聚合，不能把申请条数当装修户数。 */
@Service @RequiredArgsConstructor
public class DecorationOverviewService {
    private final DecorationScope scope;
    private final QueryHelper queries;
    public record Summary(long totalHouseCount, BigDecimal totalHouseCountRate,
                          long inDecorationHouseCount, BigDecimal inDecorationHouseCountRate,
                          long completedHouseCount, BigDecimal completedHouseCountRate,
                          BigDecimal paidDepositAmount, BigDecimal paidDepositAmountRate) {}
    public record Item(String name, long value) {}
    public record Report(Summary summary, List<Item> funnel, List<Item> houseTypePie) {}

    // 相关子查询严格使用同应用、租户、项目和房屋；不能被其他应用的更新记录压掉。
    private static final String LATEST = """
        not exists (select 1 from pd_application newer
          where newer.application_id=a.application_id and newer.tenant_id=a.tenant_id
            and newer.project_id=a.project_id and newer.space_id=a.space_id and newer.deleted=false
            and (newer.create_time>a.create_time or (newer.create_time=a.create_time and newer.id>a.id)))
        """;
    private static final String SUMMARY = """
        select count(distinct a.space_id) as total,
          count(distinct case when a.refund_status<>2 and (%s) then a.space_id end) as decorating,
          count(distinct case when a.refund_status=2 and (%s) then a.space_id end) as completed,
          coalesce(sum(case when a.payment_status=2 then a.paid_amount else 0 end),0) as paid,
          count(distinct case when a.create_time>=? and a.create_time<? then a.space_id end) as current_total,
          count(distinct case when a.create_time>=? and a.create_time<? then a.space_id end) as previous_total,
          count(distinct case when a.create_time>=? and a.create_time<? and a.refund_status<>2 then a.space_id end) as current_decorating,
          count(distinct case when a.create_time>=? and a.create_time<? and a.refund_status<>2 then a.space_id end) as previous_decorating,
          count(distinct case when a.refund_time>=? and a.refund_time<? and a.refund_status=2 then a.space_id end) as current_completed,
          count(distinct case when a.refund_time>=? and a.refund_time<? and a.refund_status=2 then a.space_id end) as previous_completed,
          coalesce(sum(case when a.paid_time>=? and a.paid_time<? and a.payment_status=2 then a.paid_amount else 0 end),0) as current_paid,
          coalesce(sum(case when a.paid_time>=? and a.paid_time<? and a.payment_status=2 then a.paid_amount else 0 end),0) as previous_paid
        from pd_application a
        """.formatted(LATEST, LATEST);
    // 材料定义与审核结果均为申请快照；未上传的选填材料不占待审核/待缴费流程。
    private static final String FUNNEL = """
        select count(case when a.deposit_review_status=0 or exists(
          select 1 from jsonb_array_elements(a.details::jsonb->'materials') m
          where m->'definition'->>'kind'='material' and (m->>'reviewStatus')::integer=0
            and ((m->'definition'->>'required')::boolean or jsonb_array_length(m->'fileIds')>0)
        ) then 1 end) as property_review,
        count(case when exists(
          select 1 from jsonb_array_elements(a.details::jsonb->'materials') m
          where m->'definition'->>'kind'='drawing' and (m->>'reviewStatus')::integer=0
            and ((m->'definition'->>'required')::boolean or jsonb_array_length(m->'fileIds')>0)
        ) then 1 end) as drawing_review,
        count(case when a.payment_status<>2 and a.deposit_review_status=1 and not exists(
          select 1 from jsonb_array_elements(a.details::jsonb->'materials') m
          where (m->>'reviewStatus')::integer<>1
            and ((m->'definition'->>'required')::boolean or jsonb_array_length(m->'fileIds')>0)
        ) then 1 end) as pending_payment,
        count(case when (a.payment_status=2 or a.permit::jsonb->>'printTime' is not null)
          and a.actual_end_time is null then 1 end) as started
        from pd_application a
        """;
    private static final String HOUSE_TYPES = """
        select a.house_type as name, count(distinct a.space_id) as value
        from pd_application a group by a.house_type order by a.house_type
        """;

    public Mono<Report> query(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scope.query(input, ctx)).flatMap(query -> {
            query.toNestQuery(nest -> nest.and("deleted", false));
            query.setPaging(false); query.getSorts().clear();
            var month = YearMonth.now(ZoneId.of("Asia/Shanghai"));
            long current = start(month), next = start(month.plusMonths(1)), previous = start(month.minusMonths(1));
            Object[] args = {current,next,previous,current,current,next,previous,current,current,next,previous,current,current,next,previous,current};
            return Mono.zip(
                queries.select(SUMMARY, SummaryRow::new, args).where(query).fetch().single(),
                queries.select(FUNNEL, FunnelRow::new).where(query).fetch().single(),
                queries.select(HOUSE_TYPES, HouseTypeRow::new).where(query).fetch()
                    .map(row -> new Item(row.getName() == null || row.getName().isBlank() ? "" : row.getName(), row.getValue())).collectList()
            ).map(tuple -> {
                var row = tuple.getT1(); var funnel = tuple.getT2();
                var summary = new Summary(row.total, rate(row.currentTotal, row.previousTotal),
                    row.decorating, rate(row.currentDecorating, row.previousDecorating), row.completed,
                    rate(row.currentCompleted, row.previousCompleted), row.paid, rate(row.currentPaid, row.previousPaid));
                return new Report(summary, List.of(new Item("propertyReview", funnel.propertyReview),
                    new Item("drawingReview", funnel.drawingReview), new Item("pendingPayment", funnel.pendingPayment),
                    new Item("started", funnel.started), new Item("completed", row.completed)), tuple.getT3());
            });
        });
    }
    private static long start(YearMonth month) { return month.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    private static BigDecimal rate(long current, long previous) { return rate(BigDecimal.valueOf(current), BigDecimal.valueOf(previous)); }
    private static BigDecimal rate(BigDecimal current, BigDecimal previous) {
        return previous.signum() == 0 ? (current.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(100))
            : current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
    }
    /** QueryHelper 聚合行映射，仅标量结果，不读取申请详情全集。 */
    @Getter @Setter public static class SummaryRow {
        private long total, decorating, completed, currentTotal, previousTotal, currentDecorating, previousDecorating, currentCompleted, previousCompleted;
        private BigDecimal paid, currentPaid, previousPaid;
    }
    @Getter @Setter public static class FunnelRow { private long propertyReview, drawingReview, pendingPayment, started; }
    @Getter @Setter public static class HouseTypeRow { private String name; private long value; }
}

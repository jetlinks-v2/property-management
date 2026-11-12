package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.stream.IntStream;

/** 当前待办按申请去重；累计房屋、自然月申请和结束数量各自保留单位与口径。 */
@Service @RequiredArgsConstructor
public class DecorationDashboardService {
    private final DecorationScope scope;
    private final QueryHelper queries;
    public record Metric(String key, String group, long value) {}
    /** 月份用ISO年月标识；无申请的月份也返回零值，不用退款时间替代结束时间。 */
    public record Month(String month, long created, long ended) {}
    public record Report(long asOf, List<Metric> metrics, List<Month> trend) {}

    public Mono<Report> query(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scope.query(input, ctx)).flatMap(query -> {
            query.toNestQuery(nest -> nest.and("deleted", false));
            query.setPaging(false); query.getSorts().clear();
            long asOf = System.currentTimeMillis();
            var parameters = new ArrayList<Object>();
            var columns = new ArrayList<String>();
            // 仅拼接服务端枚举的固定表达式，不拼接用户SQL；一个聚合查询不加载申请详情全集。
            for (var metric : DecorationOverviewMetric.values()) {
                var condition = metric.condition(asOf);
                columns.add("count(case when " + condition.sql() + " then 1 end) as " + metric.name().toLowerCase(java.util.Locale.ROOT));
                parameters.addAll(condition.parameters());
            }
            var zone = ZoneId.of("Asia/Shanghai");
            var firstMonth = YearMonth.from(Instant.ofEpochMilli(asOf).atZone(zone)).minusMonths(11);
            // 与摘要共享同一授权聚合，固定12个月、绑定时间范围；不逐月查库或加载申请全集。
            for (int i = 0; i < 12; i++) {
                var month = firstMonth.plusMonths(i);
                long start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
                long end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
                columns.add("count(case when a.create_time>=? and a.create_time<? then 1 end) as trendcreated" + i);
                columns.add("count(case when a.refund_status=2 and a.actual_end_time>=? and a.actual_end_time<? then 1 end) as trendended" + i);
                parameters.addAll(List.of(start, end, start, end));
            }
            return queries.select("select " + String.join(",", columns) + " from pd_application a", parameters.toArray())
                .where(query).fetch().single()
                .map(row -> new Report(asOf, Arrays.stream(DecorationOverviewMetric.values())
                    .map(metric -> new Metric(metric.name(), metric.group, ((Number) row.get(metric.name().toLowerCase(java.util.Locale.ROOT)).orElseThrow()).longValue()))
                    .toList(), IntStream.range(0, 12).mapToObj(i -> new Month(firstMonth.plusMonths(i).toString(),
                        ((Number) row.get("trendcreated" + i).orElseThrow()).longValue(),
                        ((Number) row.get("trendended" + i).orElseThrow()).longValue())).toList()));
        });
    }
}

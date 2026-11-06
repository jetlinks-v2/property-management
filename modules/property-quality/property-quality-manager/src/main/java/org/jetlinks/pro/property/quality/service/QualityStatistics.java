package org.jetlinks.pro.property.quality.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.quality.api.QualityReportFilter;
import org.jetlinks.pro.property.quality.entity.QualityRecordEntity;
import org.jetlinks.pro.property.quality.entity.QualityRectificationEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原报表的任务、检查项、整改率和条线环比口径。
 * 任务和工单各自走资产查询；只累计维度汇总，检查项与整改集合限定在单工单最多一千项。
 * 复验待审及通过算已整改，驳回算未整改，保持旧 SQL 的状态 2/3 语义。
 */
@Service
@RequiredArgsConstructor
public class QualityStatistics {
    private final QualityTaskManager taskAccess;
    private final QualityTaskService tasks;
    private final QualityWorkOrders orderAccess;
    private final QualityWorkOrderService orders;
    private final QualityRecordService records;
    private final QualityRectificationService rectifications;
    private final QualityDirectory directory;

    public Flux<Map<String, Object>> tasks(QualityReportFilter filter) {
        return taskAccess.query(taskQuery(filter)).flatMapMany(q -> tasks.createQuery().setParam(q).fetch())
            .collect(LinkedHashMap<String, Map<String, Object>>::new, (groups, task) -> {
                String key = filter.getTaskLevel() == 1 ? Objects.toString(task.getBusinessTypeId(), "") : task.getProjectId();
                var row = groups.computeIfAbsent(key, ignored -> dimension(filter.getTaskLevel(), key,
                    filter.getTaskLevel() == 1 ? task.getBusinessTypeName() : task.getProjectName()));
                QualityReportRules.add(row, "totalTask", 1);
                if (task.getTaskStatus() == 2) QualityReportRules.add(row, "completeTask", 1);
                if (task.getTaskStatus() == 3) QualityReportRules.add(row, "closeTask", 1);
            }).flatMap(groups -> includeProjects(filter, groups))
            .flatMapMany(groups -> Flux.fromIterable(groups.values()))
            .concatMap(row -> {
                // 无授权任务的项目不可能通过 taskQuery 关联到检查项；保留零值行，省去空明细查询。
                if (QualityReportRules.number(row, "totalTask") == 0) return Mono.just(QualityReportRules.taskRates(row));
                var dimensionFilter = copy(filter);
                if (filter.getTaskLevel() == 1) dimensionFilter.setBusinessTypeId(row.get("businessTypeId").toString());
                else dimensionFilter.setProjectId(row.get("projectId").toString());
                // 旧任务总表不按条线时间窗口筛选；日期仅属于条线统计及问题明细。
                dimensionFilter.setStartTime(null); dimensionFilter.setEndTime(null);
                return entries(dimensionFilter).reduce(row, (summary, entry) -> {
                    if (Integer.valueOf(1).equals(entry.record().getIsQualified())) QualityReportRules.add(summary, "qualifiedContentCount", 1);
                    else {
                        QualityReportRules.add(summary, "unqualifiedContentCount", 1);
                        if (entry.rectified()) QualityReportRules.add(summary, "totalRectificationCount", 1);
                    }
                    return summary;
                }).map(QualityReportRules::taskRates);
            });
    }

    public Flux<Map<String, Object>> lines(QualityReportFilter filter) {
        validateWindow(filter);
        Mono<Map<String, Map<String, Object>>> current = lineGroups(filter);
        Mono<Map<String, Map<String, Object>>> previous = Mono.just(Map.of());
        if (filter.getTaskLevel() == 1 && filter.getStartTime() != null && filter.getEndTime() != null) {
            var before = copy(filter);
            long start = filter.getStartTime() / 1000 * 1000, end = filter.getEndTime() / 1000 * 1000;
            before.setStartTime(start - (end - start + 1000)); before.setEndTime(start - 1000);
            previous = lineGroups(before);
        }
        return Mono.zip(current, previous).flatMapMany(tuple -> Flux.fromIterable(tuple.getT1().entrySet()).map(entry -> {
            var previousRow = tuple.getT2().get(entry.getKey());
            Long count = filter.getTaskLevel() == 1 && filter.getStartTime() != null && filter.getEndTime() != null
                ? previousRow == null ? 0L : QualityReportRules.number(previousRow, "problemCount") : null;
            return QualityReportRules.lineRates(entry.getValue(), count);
        }));
    }

    private Mono<Map<String, Map<String, Object>>> lineGroups(QualityReportFilter filter) {
        return entries(filter).collect(() -> (Map<String, Map<String, Object>>) new LinkedHashMap<String, Map<String, Object>>(), (groups, entry) -> {
            var record = entry.record();
            var row = groups.computeIfAbsent(record.getSubjectParentId(), key -> {
                var value = new LinkedHashMap<String, Object>();
                value.put("lineId", key); value.put("lineName", record.getSubjectParentName());
                for (String count : List.of("score", "problemCount", "rectifiedCount")) value.put(count, 0L);
                return value;
            });
            QualityReportRules.add(row, "score", record.getScore() == null ? 0 : record.getScore());
            if (Integer.valueOf(0).equals(record.getIsQualified())) {
                QualityReportRules.add(row, "problemCount", 1);
                if (entry.rectified()) QualityReportRules.add(row, "rectifiedCount", 1);
            }
        });
    }

    private record Entry(QualityRecordEntity record, boolean rectified) {}

    private Flux<Entry> entries(QualityReportFilter filter) {
        return taskAccess.query(taskQuery(filter)).flatMap(taskQuery -> {
            var query = QueryParamEntity.newQuery().where("taskId", QualityTaskTermBuilder.TYPE, taskQuery.getTerms()).getParam();
            query.setPaging(false);
            return orderAccess.query(query);
        }).flatMapMany(q -> orders.createQuery().setParam(q).fetch()).concatMap(order -> {
            var query = records.createQuery().where("workOrderId", order.getId()).and("deleted", false).and("recordStatus", 2);
            if (filter.getLineId() != null) query.and("subjectParentId", filter.getLineId());
            if (filter.getStartTime() != null) query.gte("completeTime", filter.getStartTime());
            if (filter.getEndTime() != null) query.lte("completeTime", filter.getEndTime());
            return Mono.zip(query.fetch().take(1001).collectList(), rectifications.createQuery().where("workOrderId", order.getId())
                .and("deleted", false).orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc("createTime"))
                .fetch().take(1001).collectList()).flatMapMany(tuple -> {
                    QualityScope.require(tuple.getT1().size() <= 1000 && tuple.getT2().size() <= 1000, "export_limit");
                    return Flux.fromIterable(tuple.getT1()).map(record -> new Entry(record, rectified(record, tuple.getT2())));
                });
        }, 1);
    }

    private static boolean rectified(QualityRecordEntity record, List<QualityRectificationEntity> rows) {
        return rows.stream().filter(row -> row.getRecordIds().contains(record.getId())).findFirst()
            .map(row -> row.getRectificationStatus() == 2 || row.getRectificationStatus() == 3).orElse(false);
    }

    private Mono<LinkedHashMap<String, Map<String, Object>>> includeProjects(QualityReportFilter filter, LinkedHashMap<String, Map<String, Object>> groups) {
        if (filter.getTaskLevel() == 1) return Mono.just(groups);
        return directory.projects().filter(project -> filter.getProjectId() == null || filter.getProjectId().equals(project.getId()))
            .doOnNext(project -> groups.computeIfAbsent(project.getId(), key -> dimension(2, key, project.getName())))
            .then(Mono.just(groups));
    }

    static QueryParamEntity taskQuery(QualityReportFilter filter) {
        var query = QueryParamEntity.newQuery().where("taskLevel", filter.getTaskLevel()).getParam();
        query.setPaging(false);
        if (filter.getProjectId() != null) query.toQuery().and("projectId", filter.getProjectId());
        if (filter.getBusinessTypeId() != null) {
            if (filter.getBusinessTypeId().isEmpty()) query.toQuery().isNull("businessTypeId");
            else query.toQuery().and("businessTypeId", filter.getBusinessTypeId());
        }
        return query;
    }

    private static Map<String, Object> dimension(int level, String id, String name) {
        var row = new LinkedHashMap<String, Object>();
        row.put(level == 1 ? "businessTypeId" : "projectId", id);
        row.put(level == 1 ? "businessTypeName" : "projectName", name);
        for (String key : List.of("totalTask", "completeTask", "closeTask", "qualifiedContentCount", "unqualifiedContentCount", "totalRectificationCount")) row.put(key, 0L);
        return row;
    }

    private static QualityReportFilter copy(QualityReportFilter source) {
        var result = new QualityReportFilter(); result.setTaskLevel(source.getTaskLevel()); result.setProjectId(source.getProjectId());
        result.setBusinessTypeId(source.getBusinessTypeId()); result.setLineId(source.getLineId()); result.setStartTime(source.getStartTime()); result.setEndTime(source.getEndTime());
        return result;
    }

    private static void validateWindow(QualityReportFilter filter) {
        QualityScope.require(filter.getStartTime() == null || filter.getEndTime() == null || filter.getStartTime() <= filter.getEndTime(), "date_range");
    }
}

package org.jetlinks.pro.patrol.service.statistics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;

import java.time.LocalDate;
import java.util.List;

/** 巡更履行统计的只读契约。次数、点位日任务和检查结果分开，人员仅统计真实执行贡献。 */
public final class PatrolOverview {
    private PatrolOverview() {}

    /** 日期为规则归属日；项目仅收窄 AssetsHolder 已授权范围。 */
    public record Range(@NotNull LocalDate from, @NotNull LocalDate to,
                        @Size(max = 100) List<String> categoryIds, @Size(max = 100) List<String> levelIds,
                        @Size(max = 64) String projectId) {}
    public enum Metric { all, completed, finished, due, overdue, notDue, missed, absent, anomaly, pending, ongoing }
    public record Drill(@NotNull @Valid Range range, @NotNull Metric metric,
                        @Min(0) int pageIndex, @Min(1) @Max(100) int pageSize) {}
    public record DetailRequest(@NotNull @Valid Range range, @NotBlank @Size(max = 64) String pointId) {}

    /** completed 含审核补计；executed 是实际打卡，credited 单列；后三种次数始终可对账至 expected。 */
    public record Counts(long expected, long completed, long executed, long credited, long overdue, long notDue,
                         long pointDays, long completedPoints, long missedPoints, long absentPoints,
                         long pendingPoints, long ongoingPoints, long abnormalPoints,
                         long dueExpected, long dueCompleted) {
        /** 汇总后重算比率，不能平均项目百分比；无应巡任务不显示为 0% 或 100%。 */
        @com.fasterxml.jackson.annotation.JsonProperty
        public Double progressRate() { return percent(completed, expected); }

        /** 仅取已截止时段，未截止时段即使已打卡也不进入分子；有效完成含审核补计，不代表按时打卡率。 */
        @com.fasterxml.jackson.annotation.JsonProperty
        public Double dueFulfillmentRate() { return percent(dueCompleted, dueExpected); }

        private static Double percent(long numerator, long denominator) {
            return denominator == 0 ? null : Math.round(10000.0 * numerator / denominator) / 100.0;
        }
    }
    public record Project(String projectId, String projectName, Counts counts) {}
    public record Day(String date, Counts counts) {}
    public record Person(String userId, String userName, long executionCount, long abnormalCount,
                         long pointCount, long lastTime) {}
    /** 一个项目、点位和归属日的履行单元；groupIds 保留生成时的真实标识，不能代替物理时段 ID。 */
    public record PointDay(String id, String projectId, String projectName, String pointId, String pointName,
                           String date, List<String> categoryNames, List<String> levelNames, List<String> groupIds,
                           String state, Counts counts) {}
    public record Report(LocalDate from, LocalDate to, long generatedAt, Counts total,
                         List<Project> projects, List<Day> days, List<Person> people) {}
    public record Detail(List<PatrolTaskEntity> periods, List<PatrolExecutionEntity> executions) {}
}

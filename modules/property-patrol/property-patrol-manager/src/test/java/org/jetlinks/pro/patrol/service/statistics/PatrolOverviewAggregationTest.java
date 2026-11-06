package org.jetlinks.pro.patrol.service.statistics;

import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.jetlinks.pro.patrol.service.statistics.PatrolOverview.*;

class PatrolOverviewAggregationTest {
    final long now = 100000;
    final Range range = new Range(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), List.of(), List.of(), null);
    PatrolTaskEntity task(String id, String point, String day, long end, int expected, int completed) {
        var task = new PatrolTaskEntity();
        task.setId(id); task.setProjectId("project"); task.setProjectName("项目"); task.setPointId(point); task.setPointName(point);
        task.setBelongDate(day); task.setBusinessDate(day); task.setTaskGroupId("group-" + id);
        task.setStartTime(end - 1000); task.setEndTime(end); task.setRequiredCount(expected); task.setCompletedCount(completed);
        return task;
    }
    PatrolExecutionEntity fact(String id, String task, PatrolExecutionEntity.CheckResult result) {
        var fact = new PatrolExecutionEntity(); fact.setId(id); fact.setTaskId(task); fact.setProjectId("project"); fact.setPointId("point");
        fact.setUserId("alice"); fact.setUserName("Alice"); fact.setReceiveTime(now - 2000); fact.setResult(result); return fact;
    }
    @Test void balancesCountsAndSeparatesAbsenceMissedAndNotYetDueAcrossRoles() {
        var tasks = List.of(task("a", "shared", "2026-11-05", now - 1, 2, 1),
                task("b", "shared", "2026-11-05", now + 1000, 3, 0),
                task("c", "absent", "2026-11-05", now, 2, 0),
                task("d", "open", "2026-11-05", now - 1, 1, 0),
                task("e", "open", "2026-11-05", now + 1, 2, 0),
                task("f", "future", "2026-11-05", now + 5000, 1, 0));
        var result = PatrolOverviewAggregation.aggregate(range, tasks, List.of(fact("visit", "a", PatrolExecutionEntity.CheckResult.fail)), now);
        var c = result.report().total();
        assertEquals(11, c.expected()); assertEquals(1, c.completed()); assertEquals(4, c.overdue()); assertEquals(6, c.notDue());
        assertEquals(c.expected(), c.completed() + c.overdue() + c.notDue());
        assertEquals(4, c.pointDays()); assertEquals(1, c.missedPoints()); assertEquals(1, c.absentPoints());
        assertEquals(1, c.ongoingPoints()); assertEquals(1, c.pendingPoints()); assertEquals(1, c.abnormalPoints());
        var shared = result.points().stream().filter(p -> p.pointId().equals("shared")).findFirst().orElseThrow();
        assertEquals(List.of("group-a", "group-b"), shared.groupIds()); assertEquals("missed", shared.state());
        assertEquals(1, result.report().people().get(0).executionCount());
    }
    @Test void countsCreditsWithoutInventingPersonnelAndDoesNotTreatAnomaliesAsIncomplete() {
        var done = task("a", "point", "2026-11-05", now - 1, 2, 2);
        var result = PatrolOverviewAggregation.aggregate(range, List.of(done), List.of(fact("visit", "a", PatrolExecutionEntity.CheckResult.fail)), now);
        var c = result.report().total();
        assertEquals(1, c.executed()); assertEquals(1, c.credited()); assertEquals(1, c.completedPoints());
        assertEquals(1, c.abnormalPoints()); assertEquals(0, c.missedPoints()); assertEquals(0, c.overdue());
        assertEquals(1, result.report().people().get(0).executionCount());
        assertTrue(PatrolOverviewAggregation.matches(result.points().get(0), Metric.finished));
        assertTrue(PatrolOverviewAggregation.matches(result.points().get(0), Metric.anomaly));
    }
    @Test void businessDatesKeepRepeatedPointsSeparateAndCompletedCountsAreClamped() {
        var first = task("a", "point", "2026-11-05", now + 2000, 2, 8);
        var second = task("b", "point", "2026-11-06", now + 5000, 1, -2); second.setBelongDate(null);
        var result = PatrolOverviewAggregation.aggregate(range, List.of(first, second), List.of(), now);
        assertEquals(2, result.report().total().pointDays()); assertEquals(2, result.report().total().completed());
        assertEquals(2, result.report().days().size()); assertEquals("2026-11-06", result.points().get(0).date());
        assertEquals(2, result.report().total().credited()); assertTrue(result.report().people().isEmpty());
    }
    @Test void aggregatesFiftyThousandPeriodsWithoutDuplicatingSharedPointDays() {
        var tasks = new ArrayList<PatrolTaskEntity>();
        for (int n = 0; n < 50000; n++) tasks.add(task("t" + n, "p" + n / 10, "2026-11-05", now + 5000, 2, 0));
        assertTimeout(java.time.Duration.ofSeconds(10), () -> {
            var result = PatrolOverviewAggregation.aggregate(range, tasks, List.of(), now);
            assertEquals(100000, result.report().total().expected()); assertEquals(5000, result.points().size());
            assertEquals(5000, result.report().total().pendingPoints());
        });
    }

    @Test void projectRatesWeightRequirementsAndExcludeOpenPeriodCompletionsFromDueRate() throws Exception {
        var small = task("small", "p1", "2026-11-05", now, 2, 1);
        var large = task("large", "p2", "2026-11-05", now - 1, 8, 8); large.setProjectId("large-project");
        var open = task("open", "p3", "2026-11-05", now + 2000, 10, 9);
        var result = PatrolOverviewAggregation.aggregate(range, List.of(small, large, open), List.of(), now);
        var total = result.report().total();
        assertEquals(90.0, total.progressRate());
        assertEquals(10, total.dueExpected()); assertEquals(9, total.dueCompleted());
        assertEquals(total.dueExpected(), total.dueCompleted() + total.overdue());
        assertEquals(90.0, total.dueFulfillmentRate());
        var project = result.report().projects().stream().filter(p -> p.projectId().equals("project")).findFirst().orElseThrow();
        assertEquals(83.33, project.counts().progressRate());
        assertEquals(50.0, project.counts().dueFulfillmentRate());
        assertTrue(PatrolOverviewAggregation.matches(result.points().stream().filter(p -> p.pointId().equals("p1")).findFirst().orElseThrow(), Metric.due));
        assertFalse(PatrolOverviewAggregation.matches(result.points().stream().filter(p -> p.pointId().equals("p3")).findFirst().orElseThrow(), Metric.due));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(total);
        assertEquals(90.0, json.get("dueFulfillmentRate").doubleValue());
        assertEquals(90.0, json.get("progressRate").doubleValue());
    }

    @Test void emptyAndNotYetDueRequirementsHaveNoMisleadingZeroOrPerfectRate() {
        var empty = PatrolOverviewAggregation.aggregate(range, List.of(), List.of(), now).report().total();
        assertNull(empty.progressRate()); assertNull(empty.dueFulfillmentRate());
        var open = PatrolOverviewAggregation.aggregate(range, List.of(task("open", "p", "2026-11-05", now + 1000, 5, 1)), List.of(), now).report().total();
        assertEquals(20.0, open.progressRate()); assertNull(open.dueFulfillmentRate());
        var missed = PatrolOverviewAggregation.aggregate(range, List.of(task("ended", "p", "2026-11-05", now, 5, 0)), List.of(), now).report().total();
        assertEquals(0.0, missed.progressRate()); assertEquals(0.0, missed.dueFulfillmentRate());
    }
}

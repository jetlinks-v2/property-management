package org.jetlinks.pro.patrol.service;

import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 用真实查询验证跨项目角色导出的拆分与原资产权限同时生效。 */
class PatrolStatisticsProjectExportTest {
    private PatrolWorkflowTest fixture;

    @BeforeEach void setup() {
        fixture = new PatrolWorkflowTest(); fixture.setup(); fixture.db.enableTenantQueries();
    }
    @AfterEach void close() { fixture.close(); }

    @Test void sharedRoleIsSplitByProjectWithoutIncludingUnauthorizedPlans() {
        var first = fixture.seedTask(2);
        first.setProjectId("project"); first.setProjectName("Project");
        fixture.tasks.createUpdate().set(PatrolTaskEntity::getProjectId, "project")
                .set(PatrolTaskEntity::getProjectName, "Project")
                .where(PatrolTaskEntity::getId, first.getId()).execute().block();
        fixture.seedPlan("second-project-plan", "north");
        fixture.plans.createUpdate().set(PatrolPlanEntity::getProjectId, "second-project")
                .where(PatrolPlanEntity::getId, "second-project-plan").execute().block();
        var second = new PatrolTaskEntity(); BeanUtils.copyProperties(first, second);
        second.setId("second-project-task"); second.setTaskGroupId("second-group");
        second.setPlanId("second-project-plan"); second.setProjectId("second-project"); second.setProjectName("第二项目");
        second.setRequiredCount(3); second.setCompletedCount(1); fixture.tasks.insert(second).block();
        fixture.seedPlan("unauthorized-plan", "south");
        var forbidden = new PatrolTaskEntity(); BeanUtils.copyProperties(second, forbidden);
        forbidden.setId("unauthorized-task"); forbidden.setTaskGroupId("forbidden-group");
        forbidden.setPlanId("unauthorized-plan"); forbidden.setRequiredCount(100); fixture.tasks.insert(forbidden).block();
        var service = new PatrolStatisticsService(fixture.tasks);
        var range = new PatrolStatisticsService.Range(LocalDate.now(), LocalDate.now(), List.of());
        var original = service.report(range, true).contextWrite(fixture.identity).block();
        assertEquals(1, original.rows().size());
        var report = service.reportByProject(range, true).contextWrite(fixture.identity).block();
        assertEquals(2, report.rows().size());
        assertEquals(List.of("Project", "第二项目"), report.rows().stream().map(PatrolStatisticsService.Row::projectName).toList());
        assertEquals(List.of(2L, 3L), report.rows().stream().map(PatrolStatisticsService.Row::expected).toList());
        assertEquals(5, report.total().expected());
        assertEquals(1, report.total().completed());
        assertEquals(2, report.total().pointCount());
    }
}

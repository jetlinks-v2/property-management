package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 原角色/全员语义在真实任务生成、SQL、事务及资产边界下的回归。 */
class PatrolEligibilityTest {
    PatrolWorkflowTest f;
    @BeforeEach void setup() { f = new PatrolWorkflowTest(); f.setup(); }
    @AfterEach void close() { f.close(); }

    PatrolRosterEntity roster(String user, String role, boolean rest) {
        var row = new PatrolRosterEntity();
        row.setId("shift"); row.setName("白班"); row.setScopeKey("app");
        row.setProjectId("project"); row.setRoleId(role); row.setUserId(user); row.setRest(rest);
        row.setBusinessDate(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        row.setStartTime(System.currentTimeMillis() - 60000); row.setEndTime(System.currentTimeMillis() + 3600000);
        f.rosters.insert(row).block(); return row;
    }

    PatrolTaskEntity assigned() {
        var task = f.seedTask(3); task.setProjectId("project");
        f.tasks.updateById(task.getId(), task).block(); return task;
    }

    @Test void roleTaskAcceptsOwnShiftUnderAnotherRoleAndRejectsOtherStaffAndRest() {
        assigned(); roster("bob", "post", false);
        StepVerifier.create(f.workflow.submit(f.submission("not-rostered")).contextWrite(f.identity))
                .expectErrorSatisfies(e -> assertEquals("error.patrol.execution.off_shift", ((BusinessException)e).getI18nCode())).verify();
        f.rosters.createUpdate().set(PatrolRosterEntity::getUserId, "alice")
                .set(PatrolRosterEntity::getRoleId, "another-role").set(PatrolRosterEntity::isRest, true)
                .where(PatrolRosterEntity::getId, "shift").execute().block();
        StepVerifier.create(f.workflow.submit(f.submission("rest")).contextWrite(f.identity))
                .expectError(BusinessException.class).verify();
        f.rosters.createUpdate().set(PatrolRosterEntity::isRest, false)
                .where(PatrolRosterEntity::getId, "shift").execute().block();
        assertNotNull(f.workflow.submit(f.submission("own-other-role-shift")).contextWrite(f.identity).block());
        assertEquals(1, f.tasks.findById("task").block().getCompletedCount());
    }

    @Test void aShiftCannotReplaceTheAssignedRole() {
        assigned(); roster("alice", "another-role", false);
        f.roles.get("post").setUserIds(List.of("bob"));
        StepVerifier.create(f.workflow.submit(f.submission("wrong-role")).contextWrite(f.identity))
                .expectErrorSatisfies(e -> assertEquals("error.patrol.execution.unassigned", ((BusinessException)e).getI18nCode())).verify();
        assertEquals(0, f.tasks.findById("task").block().getCompletedCount());
        assertEquals(0, f.executions.createQuery().count().block());
    }

    @Test void allStaffConfigurationGeneratesStableTasksAndIgnoresRoleAndRosterRestrictions() {
        f.assignmentLevel();
        f.pointAssignments().configureAll(List.of("point"),
                List.of(new PatrolPointAssignmentService.Rule("", "level", "standard")), "category")
                .contextWrite(f.identity).block();
        f.ownNewAssignments();
        var plan = f.plans.createQuery().where(org.jetlinks.pro.patrol.entity.PatrolPlanEntity::getRoleId, "")
                .fetch().blockFirst();
        assertNotNull(plan);
        var day = LocalDate.now();
        var first = f.generator.generate(plan.getId(), day).contextWrite(f.identity).collectList().block();
        assertFalse(first.isEmpty()); assertEquals("", first.get(0).getRoleId());
        assertEquals(first.stream().map(PatrolTaskEntity::getId).toList(),
                f.generator.generate(plan.getId(), day).contextWrite(f.identity).map(PatrolTaskEntity::getId).collectList().block());
        var current = first.stream().filter(t -> t.getStartTime() <= System.currentTimeMillis()
                && t.getEndTime() > System.currentTimeMillis()).findFirst().orElseThrow();
        roster("bob", "post", false); f.roles.get("post").setUserIds(List.of("bob"));
        var request = new PatrolExecutionWorkflow.Submission(current.getId(), "all-staff",
                f.submission("answers").answers(), null, null, null);
        assertNotNull(f.workflow.submit(request).contextWrite(f.identity).block());
        assertEquals(1, f.tasks.findById(current.getId()).block().getCompletedCount());
    }

    @Test void noRoleAndNoRosterStillRequiresARealTaskAndEnforcesItsTimeWindow() {
        var task = assigned();
        // 新建快照保存为全员，不能通过更新不可变 roleId 改写已有任务。
        task.setId("all-staff"); task.setPeriodNo(2); task.setRoleId("");
        task.setStartTime(task.getStartTime() + 1); f.tasks.insert(task).block();
        assertNotNull(f.workflow.checkEligibility(task, "alice", Instant.now()).contextWrite(f.identity).block());
        StepVerifier.create(f.workflow.checkEligibility(task, "alice", Instant.ofEpochMilli(task.getEndTime())).contextWrite(f.identity))
                .expectErrorSatisfies(e -> assertEquals("error.patrol.execution.outside_window", ((BusinessException)e).getI18nCode())).verify();
    }

    @Test void rosterTemplateAndImportIncludeEmployeesWithoutAnyPatrolRole() throws Exception {
        f.roles.get("post").setUserIds(List.of());
        var importer = f.rosterImporter(f.rosters);
        var month = java.time.YearMonth.now();
        byte[] template = importer.template(month).contextWrite(f.identity).block();
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(template));
             var out = new java.io.ByteArrayOutputStream()) {
            var row = book.getSheet("排班表").getRow(5);
            assertEquals("alice", row.getCell(2).getStringCellValue());
            row.createCell(4).setCellValue("休息"); book.write(out);
            assertEquals(1, importer.importMonth(month, out.toByteArray()).contextWrite(f.identity).block());
        }
        var roster = f.rosters.createQuery().fetch().blockFirst();
        assertEquals("", roster.getRoleId()); assertEquals("alice", roster.getUserId());
    }
}

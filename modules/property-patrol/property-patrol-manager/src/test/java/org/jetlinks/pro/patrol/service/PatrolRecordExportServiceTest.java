package org.jetlinks.pro.patrol.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.DefaultQueryHelper;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.web.PatrolTaskGroupController;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolProjectAssetsHolder;
import org.jetlinks.pro.patrol.integration.ProjectTestContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用真实 SQL 和资产范围验证筛选、跨批查询及展开预算，不创建漏检表。 */
class PatrolRecordExportServiceTest {
    private PatrolWorkflowTest f;
    private PatrolRecordExportService export;
    private ReactiveRepository<PatrolTaskEntity, String> tasks;
    private ReactiveRepository<PatrolExecutionEntity, String> executions;

    @BeforeEach void setup() {
        f = new PatrolWorkflowTest(); f.setup(); f.db.enableTenantQueries();
        tasks = spy(f.taskRepository);
        executions = spy(f.db.repository(PatrolExecutionEntity.class, PatrolExecutionEntity::new));
        export = new PatrolRecordExportService(tasks, executions);
    }
    @AfterEach void close() { f.close(); }

    @Test void selectedGroupsRetainRealPeriodsAndExecutionsAndExcludeUnauthorizedPlans() throws Exception {
        var first = f.seedTask(2);
        var second = new PatrolTaskEntity(); BeanUtils.copyProperties(first, second);
        second.setId("second"); second.setPeriodNo(2); second.setStartTime(first.getStartTime() + 1000);
        tasks.insert(second).block();
        var allowed = group(first);
        f.generationRepository.insert(allowed).block();
        seedExecution(first, "exec-1"); seedExecution(second, "exec-2");

        f.seedPlan("forbidden", "south");
        var forbidden = new PatrolTaskEntity(); BeanUtils.copyProperties(first, forbidden);
        forbidden.setId("forbidden-period"); forbidden.setPlanId("forbidden"); forbidden.setTaskGroupId("forbidden-group");
        tasks.insert(forbidden).block(); f.generationRepository.insert(group(forbidden)).block();
        seedExecution(forbidden, "forbidden-exec");

        var unselected = new PatrolTaskEntity(); BeanUtils.copyProperties(first, unselected);
        unselected.setId("unselected-period"); unselected.setTaskGroupId("unselected-group");
        unselected.setStartTime(first.getStartTime() + 2000);
        tasks.insert(unselected).block(); f.generationRepository.insert(group(unselected)).block();
        seedExecution(unselected, "unselected-exec");

        var service = new PatrolTaskGroupService(f.generationRepository, new DefaultQueryHelper(f.db.operator()), f.tasks);
        var controller = new PatrolTaskGroupController(service, export, mock(PatrolExportImages.class), new org.jetlinks.pro.patrol.web.PatrolRecordExportJobs());
        var selected = QueryParamEntity.newQuery().in("id", List.of(allowed.getId(), "forbidden-group")).getParam();
        var response = controller.export(selected).contextWrite(f.identity).block();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            assertEquals(3, book.getNumberOfSheets());
            assertEquals(1, book.getSheetAt(0).getLastRowNum());
            assertNull(book.getSheet("巡更时段"));
            assertEquals(2, book.getSheet("执行记录").getLastRowNum());
            assertEquals(2, book.getSheet("检查项结果").getLastRowNum());
            assertEquals("exec-1", book.getSheet("执行记录").getRow(1).getCell(8).getStringCellValue());
            assertEquals("exec-2", book.getSheet("执行记录").getRow(2).getCell(8).getStringCellValue());
        }
    }

    @Test void queryCountGrowsByBatchRatherThanByTaskGroup() {
        var rows = new ArrayList<PatrolGenerationEntity>();
        var source = f.seedTask(2);
        var periodRows = new ArrayList<PatrolTaskEntity>();
        for (int i = 0; i < 130; i++) {
            var task = new PatrolTaskEntity(); BeanUtils.copyProperties(source, task);
            task.setId("t-" + i); task.setTaskGroupId("g-" + i); task.setStartTime(source.getStartTime() + i + 1);
            periodRows.add(task); rows.add(group(task));
        }
        tasks.insertBatch(periodRows).block();
        for (var task : periodRows) seedExecution(task, "exec-" + task.getId());
        clearInvocations(tasks, executions);
        var batches = export.batches(rows).contextWrite(f.identity).collectList().block();
        assertEquals(List.of(64, 64, 2), batches.stream().map(batch -> batch.groups().size()).toList());
        assertEquals(130, batches.stream().mapToInt(batch -> batch.periods().size()).sum());
        assertEquals(130, batches.stream().mapToInt(batch -> batch.executions().size()).sum());
        assertTrue(batches.stream().flatMap(batch -> batch.periods().stream()).allMatch(task -> task.getStandardSnapshot() == null));
        assertTrue(batches.stream().flatMap(batch -> batch.executions().stream()).allMatch(row -> row.getStandardSnapshot().size() == 1));
        assertTrue(export.batches(List.of()).contextWrite(f.identity).collectList().block().isEmpty());
        verify(tasks, times(3)).createQuery(); verify(executions, times(3)).createQuery();
    }

    @Test void projectPermissionFiltersAllExportSheetsBeforeReadingDetails() throws Exception {
        var allowed = f.seedTask(1);
        f.generationRepository.insert(group(allowed)).block(); seedExecution(allowed, "allowed");
        f.seedPlan("other-project-plan", "north");
        f.plans.createUpdate().set(org.jetlinks.pro.patrol.entity.PatrolPlanEntity::getProjectId, "other-project")
                .where(org.jetlinks.pro.patrol.entity.PatrolPlanEntity::getId, "other-project-plan").execute().block();
        var other = new PatrolTaskEntity(); BeanUtils.copyProperties(allowed, other);
        other.setId("other-task"); other.setTaskGroupId("other-group"); other.setPlanId("other-project-plan");
        other.setProjectId("other-project"); tasks.insert(other).block();
        f.generationRepository.insert(group(other)).block(); seedExecution(other, "other-project-execution");
        var controller = new PatrolTaskGroupController(new PatrolTaskGroupService(f.generationRepository,
                new DefaultQueryHelper(f.db.operator()), f.tasks), export, mock(PatrolExportImages.class), new org.jetlinks.pro.patrol.web.PatrolRecordExportJobs());
        var query = new QueryParamEntity();
        // 固定导出 schema 不应被列表请求携带的字段裁剪破坏。
        query.setIncludes(java.util.Set.of("pointName")); query.setExcludes(java.util.Set.of("id"));
        var response = AssetsHolder.currentReactive().flatMap(original -> controller.export(query)
                .contextWrite(context -> context.put(AssetsHolder.class, new PatrolProjectAssetsHolder(
                        original, ProjectTestContexts.project("app"), f.supplier))))
                .contextWrite(f.identity).block();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            assertEquals(1, book.getSheetAt(0).getLastRowNum());
            assertNull(book.getSheet("巡更时段"));
            assertEquals(1, book.getSheet("执行记录").getLastRowNum());
            assertEquals("allowed", book.getSheet("执行记录").getRow(1).getCell(8).getStringCellValue());
        }
    }

    @Test void detailBudgetCountsUniqueHistoricalCheckRowsAndRejectsOverflow() {
        var execution = new PatrolExecutionEntity();
        execution.setStandardSnapshot(List.of(new PatrolStandardEntity.CheckItem("a", "历史项", true, List.of())));
        execution.setAnswers(List.of(new PatrolExecutionEntity.Answer("a", "值"), new PatrolExecutionEntity.Answer("b", "旧答案")));
        execution.setItemResults(List.of(new PatrolExecutionEntity.ItemResult("c", PatrolExecutionEntity.CheckResult.fail)));
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(PatrolRecordExportService.checkItemIds(execution)));
        var budget = new PatrolRecordExportService.DetailBudget();
        budget.add(PatrolRecordExportService.DETAIL_LIMIT - 4);
        budget.add(1 + PatrolRecordExportService.checkItemIds(execution).size());
        var failure = assertThrows(BusinessException.class, () -> budget.add(1));
        assertEquals("error.patrol_record_export_detail_limit", failure.getI18nCode());
    }

    private PatrolGenerationEntity group(PatrolTaskEntity task) {
        var group = new PatrolGenerationEntity(); group.setId(task.getTaskGroupId()); group.setPlanId(task.getPlanId());
        group.setPointId(task.getPointId()); group.setRoleId(task.getRoleId()); group.setBelongDate(task.getBelongDate());
        group.setBusinessDate(task.getBusinessDate()); group.setStartTime(task.getStartTime()); group.setEndTime(task.getEndTime());
        group.setPeriodCount(1); group.setRequiredCount(task.getRequiredCount()); return group;
    }
    private void seedExecution(PatrolTaskEntity task, String id) {
        var row = new PatrolExecutionEntity(); row.setId(id); row.setTaskGroupId(task.getTaskGroupId()); row.setTaskId(task.getId());
        row.setPlanId(task.getPlanId()); row.setPointId(task.getPointId()); row.setUserId("alice"); row.setUserName("Alice");
        row.setRequestId(id); row.setReceiveTime(task.getStartTime() + 1000); row.setStandardSnapshot(task.getStandardSnapshot());
        row.setAnswers(List.of(new PatrolExecutionEntity.Answer("lock", "normal"))); executions.insert(row).block();
    }
}

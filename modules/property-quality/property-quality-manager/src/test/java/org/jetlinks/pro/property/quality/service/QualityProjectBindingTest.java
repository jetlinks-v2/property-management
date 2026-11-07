package org.jetlinks.pro.property.quality.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.quality.entity.QualityConfigurationEntity;
import org.jetlinks.pro.property.quality.entity.QualityTaskEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QualityProjectBindingTest {
    private QualityConfigurationEntity configuration(String kind) {
        var row = new QualityConfigurationEntity();
        row.setKind(kind); row.setName("配置"); row.setConfigLevel(1); row.setStatus(1);
        row.setData(new LinkedHashMap<>(Map.of("checkContentId", "content", "score", 100)));
        return row;
    }

    @Test
    void companyTemplatesAndInspectionConfigurationsRequireRealProjectField() {
        for (String kind : List.of("template", "inspection-content")) {
            var row = configuration(kind);
            row.getData().put("businessTypeId", "old-config-id");
            assertThrows(BusinessException.class, () -> QualityConfigurationRules.validate(row));
            row.setProjectId("project-a");
            assertDoesNotThrow(() -> QualityConfigurationRules.validate(row));
        }
    }

    @Test
    void companyBasicDefinitionsRemainSharedWithoutProject() {
        for (String kind : List.of("category", "line", "subject", "content")) {
            var row = configuration(kind); row.setParentId("parent");
            row.getData().put("checkContent", "要求"); row.getData().put("photoRequirement", "照片");
            assertDoesNotThrow(() -> QualityConfigurationRules.validate(row));
        }
    }

    @Test
    void removedBusinessTypeCannotBeCreatedThroughConfigurationApi() {
        assertThrows(BusinessException.class, () -> QualityConfigurationRules.validate(configuration("business-type")));
    }

    @Test
    void bothCompanyAndProjectTasksRejectAnotherProjectsTemplateBeforePersistence() {
        for (int level : List.of(1, 2)) {
            var tasks = mock(QualityTaskService.class);
            var schedules = mock(QualityScheduleService.class);
            var scope = mock(QualityScope.class);
            var configurations = mock(QualityConfigurationManager.class);
            var directory = mock(QualityDirectory.class);
            var manager = new QualityTaskManager(tasks, schedules, scope, configurations, directory,
                mock(QualityLog.class), mock(QualityTaskCandidates.class));
            when(scope.current()).thenReturn(Mono.just(new QualityContext("tenant", "app", "user", "用户")));
            var project = new PropertyOrganizationInfo(); project.setId("project-b"); project.setName("项目B");
            when(directory.project("project-b")).thenReturn(Mono.just(project));
            var template = configuration("template");
            template.setConfigLevel(level); template.setProjectId("project-a");
            when(configurations.read("template-a")).thenReturn(Mono.just(template));
            var task = new QualityTaskEntity();
            task.setTaskName("项目B任务"); task.setTaskLevel(level); task.setTaskType(1);
            task.setProjectId("project-b"); task.setTemplateId("template-a");
            task.setLineAssignees(List.of(Map.of("subjectParentId", "line", "inspectorId", "user")));
            StepVerifier.create(manager.save(task)).expectError(BusinessException.class).verify();
            verifyNoInteractions(tasks, schedules);
        }
    }

    @Test
    void bothReportLevelsUseProjectFilterWithoutLegacyDimension() {
        for (int level : List.of(1, 2)) {
            var filter = new org.jetlinks.pro.property.quality.api.QualityReportFilter();
            filter.setTaskLevel(level); filter.setProjectId("project-a");
            var query = QualityStatistics.taskQuery(filter);
            assertTrue(query.getTerms().stream().anyMatch(t -> "projectId".equals(t.getColumn()) && "project-a".equals(t.getValue())));
            assertFalse(query.getTerms().stream().anyMatch(t -> "businessTypeId".equals(t.getColumn())));
        }
    }
}

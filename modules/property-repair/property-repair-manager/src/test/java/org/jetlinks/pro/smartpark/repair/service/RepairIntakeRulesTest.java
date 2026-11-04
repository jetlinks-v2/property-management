package org.jetlinks.pro.smartpark.repair.service;

import jakarta.validation.Validation;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairIntakeRulesTest {
    private CreateRepairRequest input() {
        CreateRepairRequest input = new CreateRepairRequest();
        input.setBusinessProjectId("project-a"); input.setCategoryId("lighting"); input.setChannelId("backoffice");
        input.setReportSource(2); input.setPositionType(2); input.setUrgency(3);
        input.setLabelIds(List.of("lamp-off")); input.setDescription("主卧灯具损坏");
        input.setContactName("王先生"); input.setContactPhone("13800138000");
        return input;
    }
    @Test void oldFormDoesNotRequireInventedTitleOrLocation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(input()).isEmpty());
        }
    }
    @Test void sourcePositionLabelsAndPhoneFollowOldFormConstraints() {
        CreateRepairRequest input = input(); input.setReportSource(3); input.setPositionType(0);
        input.setLabelIds(List.of()); input.setContactPhone("123");
        assertThrows(BusinessException.class,()->RepairFormOptions.from(Map.of()).normalize(input));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var fields = factory.getValidator().validate(input).stream().map(v -> v.getPropertyPath().toString()).toList();
            assertTrue(fields.containsAll(List.of("reportSource", "positionType", "contactPhone")));
        }
    }
    @Test void reporterIsSeparateFromRegistrationOperatorAndSummaryIsDerived() {
        RepairOrderEntity order = new RepairOrderEntity(); order.setCreatorName("前台登记员");
        RepairIntakeService.applyFields(order, input());
        assertEquals("王先生", order.getReporterName()); assertEquals("前台登记员", order.getCreatorName());
        assertEquals("业主报事", order.getSource()); assertEquals(2, order.getPositionType());
        assertEquals(3, order.getUrgency()); assertEquals("主卧灯具损坏", order.getTitle());
    }
    @Test void labelsCannotBeAttachedAcrossBusinessProjects() {
        var label = config("label", "project-b"); var category = config("category", "project-a");
        assertEquals("error.repair.configuration_project_mismatch", assertThrows(BusinessException.class,
            () -> RepairConfigurationRules.parent(label, category)).getMessage());
    }
    @Test void descriptionMustBelongToLabelAndParentCannotBeItself() {
        var description = config("description", "project-a"); description.setId("choice");
        var category = config("category", "project-a"); category.setId("lighting");
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.parent(description, category));
        category.setId("choice"); assertThrows(BusinessException.class, () -> RepairConfigurationRules.parent(description, category));
    }
    @Test void reviewedLevelNeedsRoleAndCoefficientKeepsOldRange() {
        var level = config("level", "project-a"); level.setName("紧急"); level.setConfiguration(Map.of("coefficient", 1000, "color", "#E5484D"));
        assertThrows(BusinessException.class, () -> RepairConfigurationRules.validate(level));
        level.setConfiguration(Map.of("color", "#E5484D", "coefficient", 10, "auditRequired", true));
        assertEquals("error.repair.audit_role_required", assertThrows(BusinessException.class,
            () -> RepairConfigurationRules.validate(level)).getMessage());
        level.setConfiguration(Map.of("color", "#E5484D", "coefficient", 10, "auditRequired", true, "auditRoleId", "reviewer"));
        assertDoesNotThrow(() -> RepairConfigurationRules.validate(level));
    }
    private RepairConfigurationEntity config(String kind, String project) {
        var entity = new RepairConfigurationEntity(); entity.setKind(kind); entity.setBusinessProjectId(project); return entity;
    }
}

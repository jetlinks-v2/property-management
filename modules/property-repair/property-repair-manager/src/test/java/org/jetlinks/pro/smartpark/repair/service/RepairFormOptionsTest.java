package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.api.PublicRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairFormOptionsTest {
    @Test void existingProjectDefaultsToAllFieldsAndRequiredLabels() {
        var options=RepairFormOptions.from(Map.of());
        assertTrue(options.values().values().stream().allMatch(Boolean::booleanValue));
        var input=new CreateRepairRequest();input.setLabelIds(List.of());
        assertThrows(BusinessException.class,()->options.normalize(input));
        assertThrows(BusinessException.class,()->options.description("",List.of()));
    }
    @Test void descriptionChoiceRequiresLabelsAndAtLeastOneDescriptionEntryRemains() {
        assertThrows(BusinessException.class,()->RepairFormOptions.from(Map.of("showReportLabel",false)));
        assertThrows(BusinessException.class,()->RepairFormOptions.from(Map.of("showReportDescriptionChoice",false,"showProblemDescription",false)));
        assertThrows(BusinessException.class,()->RepairFormOptions.from(Map.of("showMediaUpload",1)));
        assertDoesNotThrow(()->RepairFormOptions.from(Map.of("showReportLabel",false,"showReportDescriptionChoice",false)));
    }
    @Test void hiddenEmployeeFieldsAreClearedBeforeReferencesAndFilesAreValidated() {
        var options=RepairFormOptions.from(Map.of("showReportPosition",false,"showReportDetailLocation",false,"showReportUrgency",false,
            "showReportLabel",false,"showReportDescriptionChoice",false,"showMediaUpload",false));
        var input=new CreateRepairRequest();input.setPositionType(2);input.setSpaceId("foreign-room");input.setLocation("hidden-location");input.setUrgency(3);
        input.setLabelIds(List.of("foreign-label"));input.setDescriptionChoiceIds(List.of("foreign-description"));input.setAttachmentIds(List.of("foreign-file"));input.setContactName("报事人");
        options.normalize(input);
        assertEquals(1,input.getPositionType());assertNull(input.getSpaceId());assertNull(input.getLocation());assertNull(input.getUrgency());
        assertTrue(input.getLabelIds().isEmpty());assertTrue(input.getDescriptionChoiceIds().isEmpty());assertTrue(input.getAttachmentIds().isEmpty());assertEquals("报事人",input.getContactName());
    }
    @Test void anonymousHiddenFieldsNeverRemoveQrAuthorizedSpace() {
        var options=RepairFormOptions.from(Map.of("showReportPosition",false,"showReportDetailLocation",false,"showReportUrgency",false,
            "showReportLabel",false,"showReportDescriptionChoice",false,"showMediaUpload",false));
        var input=new PublicRepairRequest();input.setSpaceId("bound-room");input.setUrgency(3);input.setLocation("hidden-location");
        input.setLabelIds(List.of("hidden-label"));input.setDescriptionChoiceIds(List.of("hidden-description"));input.setUploadIds(List.of("foreign-receipt"));
        options.normalize(input);
        assertEquals("bound-room",input.getSpaceId());assertNull(input.getUrgency());assertNull(input.getLocation());assertTrue(input.getUploadIds().isEmpty());assertTrue(input.getLabelIds().isEmpty());
    }
    @Test void hiddenManualDescriptionUsesTrustedChoicesAndEnforcesLength() {
        var options=RepairFormOptions.from(Map.of("showProblemDescription",false));
        assertEquals("阀门渗水\n需要维修",options.description("伪造的客户端正文",List.of("阀门渗水","需要维修")));
        assertThrows(BusinessException.class,()->options.description("伪造正文",List.of()));
        assertThrows(BusinessException.class,()->options.description("短文",List.of("修".repeat(801))));
    }
    @Test void databaseSingletonKeyBelongsOnlyToFormOptionsInTheSameProject() {
        var config=new RepairConfigurationEntity();config.setKind("intakeOptions");config.setBusinessProjectId("project-a");config.setEnabled(true);config.setConfiguration(Map.of());
        RepairConfigurationRules.validate(config);assertEquals("project-a",config.getFormOptionsProject());
        config.setKind("channel");RepairConfigurationRules.validate(config);assertNull(config.getFormOptionsProject());
    }
}

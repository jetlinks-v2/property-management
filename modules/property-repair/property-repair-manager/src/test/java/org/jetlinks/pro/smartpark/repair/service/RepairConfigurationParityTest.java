package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairConfigurationParityTest {
    private final RepairContext context = new RepairContext("tenant", "app", "registrar", "登记员工");
    private RepairConfigurationEntity config(String id,String kind,Map<String,Object> data) {
        var row=new RepairConfigurationEntity();row.setId(id);row.setKind(kind);row.setName(id);
        row.setBusinessProjectId("project");row.setEnabled(true);row.setConfiguration(data);return row;
    }
    private RepairOrderEntity order() {
        var order=new RepairOrderEntity();order.setBusinessProjectId("project");return order;
    }
    @Test void satisfactionUsesConfiguredLongScoreRatherThanStarPosition() {
        var manager=mock(RepairConfigurationManager.class);
        var rating=config("satisfied","satisfaction",Map.of("score",80L));rating.setName("满意");
        when(manager.enabled("satisfied","satisfaction","project",context)).thenReturn(Mono.just(rating));
        var service=new RepairCommerceService(mock(RepairConfigurationService.class),manager);
        var request=new RepairActionRequest();request.setSatisfactionId("satisfied");request.setOwnerName("业主");request.setOwnerPhone("13800000000");
        request.setSatisfactionName("伪造快照");var order=order();
        StepVerifier.create(service.prepare("ownerEvaluate",order,request,context)).verifyComplete();
        assertEquals(80L,order.getOwnerSatisfaction());assertEquals("满意",order.getOwnerSatisfactionName());
        assertEquals("registrar",order.getOwnerEvaluationRecorderId());assertEquals("业主",order.getEvaluationOwnerName());
        assertEquals("满意",request.getSatisfactionName());assertTrue(request.getEvaluationLabelNames().isEmpty());
    }
    @Test void mismatchedScoreOrLabelCannotRecordEvaluation() {
        var manager=mock(RepairConfigurationManager.class);
        when(manager.enabled("good","satisfaction","project",context)).thenReturn(Mono.just(config("good","satisfaction",Map.of("score",90))));
        when(manager.enabled("bad-label","evaluationLabel","project",context)).thenReturn(Mono.just(config("bad-label","evaluationLabel",Map.of("satisfactionId","poor"))));
        var service=new RepairCommerceService(mock(RepairConfigurationService.class),manager);
        var input=new RepairActionRequest();input.setSatisfactionId("good");input.setSatisfaction(1L);
        var order=order();
        StepVerifier.create(service.prepare("evaluate",order,input,context)).expectError(BusinessException.class).verify();
        assertNull(order.getStaffEvaluated());
        input.setSatisfaction(null);input.setEvaluationLabelIds(List.of("bad-label"));
        StepVerifier.create(service.prepare("evaluate",order,input,context)).expectError(BusinessException.class).verify();
        assertNull(order.getStaffEvaluated());
    }
    @Test void callbackStoresRatingSnapshotWithoutSettingEitherEvaluationSide() {
        var manager=mock(RepairConfigurationManager.class);
        when(manager.enabled("zero","satisfaction","project",context)).thenReturn(Mono.just(config("zero","satisfaction",Map.of("score",0))));
        var service=new RepairCommerceService(mock(RepairConfigurationService.class),manager);
        var input=new RepairActionRequest();input.setSatisfactionId("zero");var order=order();
        StepVerifier.create(service.prepare("callback",order,input,context)).verifyComplete();
        assertEquals(0L,input.getSatisfaction());assertNull(order.getOwnerEvaluated());assertNull(order.getStaffEvaluated());
    }
    @Test void replyResolvesTextAndRejectsAnotherProcessingNode() {
        var manager=mock(RepairConfigurationManager.class);
        when(manager.enabled("reply","reply","project",context)).thenReturn(Mono.just(config("reply","reply",Map.of("staticNode",5,"content","已到现场，正在处理"))));
        var service=new RepairCommerceService(mock(RepairConfigurationService.class),manager);
        var input=new RepairActionRequest();input.setReplyId("reply");input.setReply("伪造正文");
        StepVerifier.create(service.prepare("follow",order(),input,context)).verifyComplete();
        assertEquals("已到现场，正在处理",input.getReply());
        StepVerifier.create(service.prepare("finish",order(),input,context)).expectError(BusinessException.class).verify();
    }
    @Test void configurationPreservesEightHundredCharacterDescriptionAndLongScores() {
        var description=config("description","description",Map.of("content","修".repeat(800)));
        assertDoesNotThrow(()->RepairConfigurationRules.validate(description));
        description.setConfiguration(Map.of("content","修".repeat(801)));
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(description));
        var satisfaction=config("good","satisfaction",Map.of("score",100));
        RepairConfigurationRules.validate(satisfaction);assertEquals(100L,satisfaction.getSatisfactionScore());
        satisfaction.setConfiguration(Map.of("score",1.5));
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(satisfaction));
        assertThrows(BusinessException.class,()->RepairConfigurationRules.score("9223372036854775808"));
    }
    @Test void deletionProtectsOrderAndHistoricalActionReferences() {
        var order=order();order.setDescriptionChoiceIds(List.of("description"));
        assertTrue(RepairConfigurationManager.references(order,"description"));
        assertTrue(RepairConfigurationManager.detailsReference(Map.of("evaluationLabelIds",List.of("label")),"label"));
        assertTrue(RepairConfigurationManager.detailsReference(Map.of("satisfactionId","level"),"level"));
        assertFalse(RepairConfigurationManager.detailsReference(Map.of("description","level"),"level"));
    }
    @Test void categoryUniqueKeysAreScopedToActualParentAndIgnoreOtherDictionaries() {
        var category=config("cat","category",Map.of("flowConfigured",false));category.setName("给排水");
        category.setCategoryParentKey("forged");category.setCategoryNameKey("forged");
        RepairConfigurationRules.validate(category);
        assertEquals("",category.getCategoryParentKey());assertEquals("给排水",category.getCategoryNameKey());
        category.setParentId(" ");RepairConfigurationRules.validate(category);assertEquals("",category.getCategoryParentKey());
        category.setParentId("parent");RepairConfigurationRules.validate(category);assertEquals("parent",category.getCategoryParentKey());
        category.setKind("channel");RepairConfigurationRules.validate(category);
        assertNull(category.getCategoryParentKey());assertNull(category.getCategoryNameKey());
    }
    @Test void cascadeDeletionProtectsHistoricalDescriptionIdentifiers() {
        assertTrue(RepairConfigurationManager.detailsReference(Map.of("descriptionChoiceIds",List.of("description")),"description"));
        assertFalse(RepairConfigurationManager.detailsReference(Map.of("descriptionChoiceIds",List.of("different")),"description"));
    }
    @Test void labelAndSatisfactionStatusIsExplicitAndDisabledIsValid() {
        var label=config("label","label",Map.of());label.setEnabled(null);
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(label));
        label.setEnabled(false);assertDoesNotThrow(()->RepairConfigurationRules.validate(label));
        var rating=config("rating","satisfaction",Map.of("score",90));rating.setEnabled(null);
        assertThrows(BusinessException.class,()->RepairConfigurationRules.validate(rating));
        rating.setEnabled(false);assertDoesNotThrow(()->RepairConfigurationRules.validate(rating));
    }
}

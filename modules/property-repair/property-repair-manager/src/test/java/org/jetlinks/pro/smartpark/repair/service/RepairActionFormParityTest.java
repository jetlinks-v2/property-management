package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 旧处理表单的可观察契约：字段长度、首次/后续跟进及签字完成内容。 */
class RepairActionFormParityTest {
    @Test void followRequiresActualContentRatherThanOnlyArrivalPhoto() {
        var input = new RepairActionRequest();
        input.setLevelId("normal"); input.setAttachmentIds(List.of("arrival-photo"));
        assertEquals("error.repair.description_required", assertThrows(BusinessException.class,
            () -> RepairWorkflowService.validateForm("follow", input)).getMessage());
        input.setDescription("   ");
        assertThrows(BusinessException.class, () -> RepairWorkflowService.validateForm("follow", input));
        input.setDescription("已到现场检查，发现阀门渗漏");
        assertDoesNotThrow(() -> RepairWorkflowService.validateForm("follow", input));
    }

    @Test void actionContentsKeepOriginalEightHundredAndTwoHundredLimits() {
        var input = new RepairActionRequest();
        for (String action : List.of("follow", "finish", "quote", "close", "review")) {
            input.setDescription("修".repeat(800));
            assertDoesNotThrow(() -> RepairWorkflowService.validateForm(action, input));
            input.setDescription("修".repeat(801));
            assertEquals("error.repair.action_content_too_long", assertThrows(BusinessException.class,
                () -> RepairWorkflowService.validateForm(action, input)).getMessage());
        }
        input.setDescription("评".repeat(200));
        assertDoesNotThrow(() -> RepairWorkflowService.validateForm("evaluate", input));
        input.setDescription("评".repeat(201));
        assertThrows(BusinessException.class, () -> RepairWorkflowService.validateForm("evaluate", input));
    }

    @Test void delayReasonAllowsEightHundredButAbandonReasonOnlyTwoHundred() {
        var input = new RepairActionRequest(); input.setReason("因".repeat(800));
        assertDoesNotThrow(() -> RepairWorkflowService.validateForm("delay", input));
        input.setReason("因".repeat(801));
        assertThrows(BusinessException.class, () -> RepairWorkflowService.validateForm("delay", input));
        input.setReason("因".repeat(200));
        assertDoesNotThrow(() -> RepairWorkflowService.validateForm("abandonApply", input));
        input.setReason("因".repeat(201));
        assertThrows(BusinessException.class, () -> RepairWorkflowService.validateForm("abandonApply", input));
    }

    @Test void signedCompletionRestoresTrustedDescriptionReplyAttachmentsAndFees() {
        var quote = new RepairProcessEntity(); quote.setDescription("已更换水阀并测试正常");
        quote.setAttachmentIds(List.of("completed-photo"));
        quote.setDetails(Map.of("replyId", "finished-reply", "reply", "已处理完成"));
        var order = new RepairOrderEntity(); order.setMaterialAmount(new BigDecimal("25.00"));
        order.setArtificialAmount(new BigDecimal("30.00"));
        var forged = new RepairActionRequest(); forged.setDescription("签字后伪造内容");
        forged.setReply("伪造回复"); forged.setReplyId("other-project-reply");
        forged.setAttachmentIds(List.of("forged-file")); forged.setMaterialAmount(BigDecimal.ZERO);
        RepairCompletionService.restore(forged, quote, order);
        assertEquals("已更换水阀并测试正常", forged.getDescription());
        assertEquals("finished-reply", forged.getReplyId()); assertEquals("已处理完成", forged.getReply());
        assertEquals(List.of("completed-photo"), forged.getAttachmentIds());
        assertEquals(new BigDecimal("25.00"), forged.getMaterialAmount());
        assertEquals(new BigDecimal("30.00"), forged.getArtificialAmount());
    }

    @Test void onlyCallbackReworkReopensSignedCompletion() {
        var callback = new RepairProcessEntity(); callback.setDetails(Map.of("rework", true));
        assertTrue(RepairCompletionService.isRework(callback));
        callback.setDetails(Map.of("rework", false)); assertFalse(RepairCompletionService.isRework(callback));
        callback.setDetails(null); assertFalse(RepairCompletionService.isRework(callback));
    }
}

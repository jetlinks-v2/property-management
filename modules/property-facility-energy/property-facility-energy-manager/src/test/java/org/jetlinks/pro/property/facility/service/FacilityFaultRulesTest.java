package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.facility.entity.FaultEntity;
import org.jetlinks.pro.property.facility.model.FaultCommand;
import org.junit.jupiter.api.Test;

class FacilityFaultRulesTest {
  FaultEntity fault() {
    var row = new FaultEntity(); row.setState("open"); row.setVersion(3L); row.setReviewerId("dufu"); return row;
  }
  FaultCommand command(String user, String comment) { return new FaultCommand(3L, comment, user, List.of("repair-photo")); }
  void act(FaultEntity row, String action, String actor, String user, String comment) {
    FacilityFaultRules.transition(row, action, command(user, comment), actor, 1000L);
  }

  @Test void projectRepairIsDispatchedClaimedRepairedAndIndependentlyAccepted() {
    var row = fault(); act(row, "dispatch", "dufu", "libai", null); assertEquals("assigned", row.getState());
    assertThrows(BusinessException.class, () -> act(row, "claim", "other", null, null));
    act(row, "claim", "libai", null, null); assertEquals("claimed", row.getState());
    act(row, "start", "libai", null, null);
    assertThrows(BusinessException.class, () -> act(row, "resolve", "libai", null, " "));
    act(row, "resolve", "libai", null, "已更换供水泵接触器并试运行正常");
    assertEquals("review", row.getState()); assertEquals(List.of("repair-photo"), row.getResolutionAttachments());
    assertThrows(BusinessException.class, () -> act(row, "approve", "libai", null, null));
    assertThrows(BusinessException.class, () -> act(row, "approve", "other", null, null));
    act(row, "approve", "dufu", null, null); assertEquals("closed", row.getState()); assertEquals(1000L, row.getCompletedTime());
    assertThrows(BusinessException.class, () -> act(row, "start", "libai", null, null));
  }

  @Test void rejectionPreservesAssigneeAndRequiresReason() {
    var row = fault(); row.setState("review"); row.setAssigneeId("libai");
    assertThrows(BusinessException.class, () -> act(row, "reject", "dufu", null, ""));
    act(row, "reject", "dufu", null, "请补充试运行照片");
    assertEquals("processing", row.getState()); assertEquals("libai", row.getAssigneeId());
  }

  @Test void replacingReviewerPreservesSubmittedEvidenceAndRequiresIndependentPersonAndReason() {
    var row = fault(); row.setState("review"); row.setAssigneeId("libai"); row.setResolution("已修复"); row.setSubmittedTime(500L);
    assertThrows(BusinessException.class, () -> act(row, "reviewer", "admin", "new-reviewer", " "));
    assertThrows(BusinessException.class, () -> act(row, "reviewer", "admin", "libai", "负责人离职"));
    act(row, "reviewer", "admin", "new-reviewer", "负责人离职");
    assertEquals("review", row.getState()); assertEquals("已修复", row.getResolution()); assertEquals(500L, row.getSubmittedTime());
    assertThrows(BusinessException.class, () -> act(row, "approve", "dufu", null, null));
    act(row, "approve", "new-reviewer", null, null); assertEquals("closed", row.getState());
  }

  @Test void newFaultCannotSkipDispatchAndClaimButLegacyUnassignedFaultCanStart() {
    assertThrows(BusinessException.class, () -> act(fault(), "start", "libai", null, null));
    var legacy = fault(); legacy.setReviewerId(null);
    act(legacy, "start", "libai", null, null);
    assertEquals("processing", legacy.getState()); assertEquals("libai", legacy.getAssigneeId());
  }

  @Test void transferClearsCurrentResultButAuditCanRetainPreviousEvidence() {
    var row = fault(); row.setState("processing"); row.setAssigneeId("libai"); row.setResolution("旧结果"); row.setStartedTime(500L);
    assertThrows(BusinessException.class, () -> act(row, "transfer", "dufu", "new-worker", null));
    act(row, "transfer", "dufu", "new-worker", "原维修人调休，交接处理");
    assertEquals("assigned", row.getState()); assertNull(row.getResolution()); assertNull(row.getStartedTime());
    assertEquals("new-worker", row.getAssigneeId());
  }

  @Test void falseAlarmClosureDoesNotCountAsRepairCompletionAndStaleVersionsFail() {
    var row = fault();
    assertThrows(BusinessException.class, () -> FacilityFaultRules.transition(row, "close", new FaultCommand(2L, "误报", null, List.of()), "dufu", 1000L));
    assertThrows(BusinessException.class, () -> act(row, "close", "dufu", null, " "));
    act(row, "close", "dufu", null, "重复登记，已关联原维修记录");
    assertEquals("cancelled", row.getState()); assertNull(row.getCompletedTime());
  }
}

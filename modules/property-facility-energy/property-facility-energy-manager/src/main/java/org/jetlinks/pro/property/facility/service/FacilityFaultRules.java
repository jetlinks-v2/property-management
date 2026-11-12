package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.util.*;
import org.jetlinks.pro.property.facility.entity.FaultEntity;
import org.jetlinks.pro.property.facility.model.FaultCommand;

/** 故障生命周期的纯业务规则；closed保留已有“验收完成”含义，误报关闭使用cancelled。 */
final class FacilityFaultRules {
  private FacilityFaultRules() {}

  static boolean finished(FaultEntity row) {
    return Set.of("closed", "cancelled").contains(row.getState());
  }

  static void transition(FaultEntity row, String action, FaultCommand command, String actor, long now) {
    require(command.version() != null && Objects.equals(row.getVersion(), command.version()), "conflict");
    require(!finished(row), "invalid_state");
    String from = row.getState(), comment = command.comment();
    switch (action) {
      case "reviewer" -> {
        require(Set.of("open", "assigned", "claimed", "processing", "review").contains(from), "invalid_state");
        require(hasText(comment), "reason_required");
        require(command.userId() != null && !command.userId().isBlank() &&
          !Objects.equals(command.userId(), row.getAssigneeId()) &&
          !Objects.equals(command.userId(), row.getReviewerId()), "invalid_review");
        row.setReviewerId(command.userId());
      }
      case "dispatch", "transfer" -> {
        require(Set.of("open", "assigned", "claimed", "processing").contains(from), "invalid_state");
        require(command.userId() != null && !command.userId().isBlank() && !Objects.equals(command.userId(), row.getAssigneeId()), "invalid_assignee");
        if ("transfer".equals(action)) require(hasText(comment), "reason_required");
        require(!Objects.equals(command.userId(), row.getReviewerId()), "invalid_review");
        row.setAssigneeId(command.userId()); row.setState("assigned");
        row.setClaimedTime(null); row.setStartedTime(null); row.setSubmittedTime(null);
        row.setResolution(null); row.setResolutionAttachments(List.of());
      }
      case "claim" -> {
        require("assigned".equals(from) && Objects.equals(actor, row.getAssigneeId()), "not_assignee");
        row.setClaimedTime(now); row.setState("claimed");
      }
      case "start" -> {
        // 仅旧版未设置验收人的open故障保留直接开始；新故障必须先派单再接单。
        require(("claimed".equals(from) || "open".equals(from) && row.getReviewerId() == null) &&
          (row.getAssigneeId() == null || Objects.equals(actor, row.getAssigneeId())), "not_assignee");
        require(!Objects.equals(actor, row.getReviewerId()), "invalid_review");
        row.setAssigneeId(actor); row.setClaimedTime(row.getClaimedTime() == null ? now : row.getClaimedTime());
        row.setStartedTime(now); row.setState("processing");
      }
      case "resolve" -> {
        require("processing".equals(from) && Objects.equals(actor, row.getAssigneeId()), "not_assignee");
        require(hasText(comment), "reason_required");
        row.setResolution(comment.trim()); row.setResolutionAttachments(command.attachments() == null ? List.of() : command.attachments());
        row.setSubmittedTime(now); row.setState("review");
      }
      case "approve", "reject" -> {
        require("review".equals(from) && !Objects.equals(actor, row.getAssigneeId()) &&
          (row.getReviewerId() == null || Objects.equals(actor, row.getReviewerId())), "invalid_review");
        if ("reject".equals(action)) { require(hasText(comment), "reason_required"); row.setState("processing"); }
        else { row.setState("closed"); row.setCompletedTime(now); }
      }
      case "close" -> { require(hasText(comment), "reason_required"); row.setCloseReason(comment.trim()); row.setState("cancelled"); }
      default -> require(false, "invalid_action");
    }
  }

  private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}

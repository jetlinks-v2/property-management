package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 资料/图纸逐项审核和逐申请保证金复核；缴费后冻结金额，不改项目协议。 */
@Service
@RequiredArgsConstructor
public class DecorationReviewService {
    private final DecorationApplicationService applications;
    private final DecorationAttachmentService attachments;
    public Mono<DecorationApplicationEntity> review(String id, DecorationRequests.Review input) {
        return applications.mutate(id, "review", input, false, (row, ctx) -> {
            editable(row); require(input.getResult() == 1 || input.getResult() == 2, "invalid_review");
            if (input.getResult() == 2) require(input.getRemark() != null && !input.getRemark().isBlank(), "remark_required");
            if ("deposit".equals(input.getItemId())) {
                require(row.getDepositReviewStatus() != 1, "review_already_approved");
                amount(input.getAmount());
                var agreement = row.getDetails().getAgreement();
                var first = agreement.getFirstRefund() == null ? java.math.BigDecimal.ZERO : agreement.getFirstRefund();
                var second = agreement.getSecondRefund() == null ? java.math.BigDecimal.ZERO : agreement.getSecondRefund();
                var refundTotal = agreement.getRefundAmount() == null ? first.add(second) : agreement.getRefundAmount();
                require(refundTotal.compareTo(input.getAmount()) <= 0, "invalid_refund_amount");
                row.setDepositReviewStatus(input.getResult()); row.setApprovedDeposit(input.getAmount());
                row.setDepositReviewerId(ctx.userId()); row.setDepositReviewerName(ctx.userName()); row.setDepositReviewTime(System.currentTimeMillis()); row.setDepositReviewRemark(input.getRemark());
            } else {
                var material = reviewItems(row).filter(m -> m.getDefinition().getId().equals(input.getItemId())).findFirst().orElseThrow(() -> error("invalid_materials", 400));
                require(material.getReviewStatus() != 1, "review_already_approved");
                require(input.getAnnotations().size() <= 50 && input.getAnnotations().stream().allMatch(m ->
                    Double.isFinite(m.x()) && Double.isFinite(m.y()) && Double.isFinite(m.width()) && Double.isFinite(m.height())
                    && m.x() >= 0 && m.y() >= 0 && m.width() >= 0.005 && m.height() >= 0.005
                    && m.x() + m.width() <= 1.000001 && m.y() + m.height() <= 1.000001
                    && m.text() != null && m.text().length() <= 200), "invalid_annotations");
                if (!input.getAnnotations().isEmpty() || input.getAnnotationSourceFileId() != null) {
                    require(input.getAnnotatedFileId() != null && material.getFileIds().contains(input.getAnnotationSourceFileId()), "invalid_annotations");
                }
                material.setAnnotationSourceFileId(input.getAnnotationSourceFileId());material.setAnnotations(input.getAnnotations());
                material.setReviewStatus(input.getResult()); material.setReviewerId(ctx.userId()); material.setReviewerName(ctx.userName()); material.setReviewTime(System.currentTimeMillis()); material.setRemark(input.getRemark()); material.setAnnotatedFileId(input.getAnnotatedFileId());
            }
            row.setStatus(reviewedStatus(row));
            List<String> ids = input.getAnnotatedFileId() == null ? List.of() : List.of(input.getAnnotatedFileId());
            return attachments.validate(ids, row.getProjectId(), row.getId(), ctx, false).then(attachments.consume(ids, row.getId(), ctx));
        });
    }
    public Mono<DecorationApplicationEntity> resubmit(String id, DecorationRequests.Resubmit input, boolean mine) {
        return applications.mutate(id, "resubmit", input, mine, (row, ctx) -> {
            editable(row); require("rejected".equals(row.getStatus()), "invalid_state");
            require(mine || "pc_admin".equals(row.getSource()), "pc_source_required");
            require(input.getMaterials().stream().map(DecorationRequests.MaterialUpload::getItemId).distinct().count() == input.getMaterials().size(), "invalid_materials");
            var ids = new ArrayList<String>();
            for (var upload : input.getMaterials()) {
                var material = reviewItems(row).filter(m -> m.getDefinition().getId().equals(upload.getItemId())).findFirst().orElseThrow(() -> error("invalid_materials", 400));
                require(material.getReviewStatus() == 2 && !upload.getFileIds().isEmpty(), "rejected_material_required");
                if ("self-inspection".equals(upload.getItemId())) {
                    row.getDetails().setSelfInspectionFiles(List.copyOf(upload.getFileIds()));
                    // 原签署快照保留历史正文；当前自检文件以重提交后的有权回执为准。
                }
                material.setFileIds(upload.getFileIds()); material.setReviewStatus(0); material.setReviewerId(null); material.setReviewerName(null); material.setReviewTime(null); material.setRemark(null); material.setAnnotatedFileId(null); ids.addAll(upload.getFileIds());
                // 批注属于旧图版本；历史快照保留，不能带入替换后的新图审核。
                material.setAnnotationSourceFileId(null); material.setAnnotations(List.of());
            }
            row.setStatus(reviewedStatus(row));
            return attachments.validate(ids, row.getProjectId(), row.getId(), ctx, false).then(attachments.consume(ids, row.getId(), ctx));
        });
    }
    public Mono<DecorationApplicationEntity> changeEndTime(String id, DecorationRequests.ChangeEndTime input) {
        return applications.mutate(id, "changeEndTime", input, false, (row, ctx) -> {
            active(row); require(input.getCompletionTime() >= row.getDetails().getStartTime(), "invalid_dates");
            row.getDetails().setCompletionTime(input.getCompletionTime()); row.setCompletionTime(input.getCompletionTime());
            if (row.getInspectionPlan() != null) {
                require(input.getCompletionTime() >= row.getInspectionPlan().getStartTime(), "invalid_dates");
                // 竣工日期变化同步未来巡检；已生成任务保留快照。到期后延期，从当天恢复而不补造过去任务。
                row.getInspectionPlan().setEndTime(input.getCompletionTime());
                long now = System.currentTimeMillis();
                if (row.getNextInspectionTime() == null && input.getCompletionTime() >= now) {
                    row.setNextInspectionTime(DecorationInspectionGenerator.dayStart(now));
                }
            }
            return Mono.empty();
        });
    }
    /** 退费完成才结束办理；此处仅更正已完成申请的结束时间，不改变状态及房屋占用。 */
    public Mono<DecorationApplicationEntity> changeActualEndTime(String id, DecorationRequests.ChangeActualEndTime input) {
        return applications.mutate(id, "changeActualEndTime", input, false, (row, ctx) -> {
            require(row.getRefundStatus() == 2, "refund_completion_required");
            require(input.getActualEndTime() >= row.getDetails().getStartTime(), "invalid_dates");
            row.setActualEndTime(input.getActualEndTime());
            return Mono.empty();
        });
    }
    /** 保留既有动作契约供补充结束说明，禁止用手动结束代替退费完成。 */
    public Mono<DecorationApplicationEntity> end(String id, DecorationRequests.Action input) {
        return applications.mutate(id, "end", input, false, (row, ctx) -> {
            require(row.getRefundStatus() == 2, "refund_completion_required");
            require(input.getRemark() != null && !input.getRemark().isBlank(), "remark_required");
            row.setEndReason(input.getRemark()); return Mono.empty();
        });
    }
}

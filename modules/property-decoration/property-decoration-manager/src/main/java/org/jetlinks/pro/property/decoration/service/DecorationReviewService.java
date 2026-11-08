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
                amount(input.getAmount());
                var agreement = row.getDetails().getAgreement();
                var first = agreement.getFirstRefund() == null ? java.math.BigDecimal.ZERO : agreement.getFirstRefund();
                var second = agreement.getSecondRefund() == null ? java.math.BigDecimal.ZERO : agreement.getSecondRefund();
                var refundTotal = agreement.getRefundAmount() == null ? first.add(second) : agreement.getRefundAmount();
                require(refundTotal.compareTo(input.getAmount()) <= 0, "invalid_refund_amount");
                row.setDepositReviewStatus(input.getResult()); row.setApprovedDeposit(input.getAmount());
                row.setDepositReviewerId(ctx.userId()); row.setDepositReviewerName(ctx.userName()); row.setDepositReviewTime(System.currentTimeMillis()); row.setDepositReviewRemark(input.getRemark());
            } else {
                var material = row.getDetails().getMaterials().stream().filter(m -> m.getDefinition().getId().equals(input.getItemId())).findFirst().orElseThrow(() -> error("invalid_materials", 400));
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
                var material = row.getDetails().getMaterials().stream().filter(m -> m.getDefinition().getId().equals(upload.getItemId())).findFirst().orElseThrow(() -> error("invalid_materials", 400));
                require(material.getReviewStatus() == 2 && !upload.getFileIds().isEmpty(), "rejected_material_required");
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
            row.getDetails().setCompletionTime(input.getCompletionTime()); row.setCompletionTime(input.getCompletionTime()); return Mono.empty();
        });
    }
    /** 实际结束时间可纠正；结束原因、计划日期和财务回执保持各自动作的所有权。 */
    public Mono<DecorationApplicationEntity> changeActualEndTime(String id, DecorationRequests.ChangeActualEndTime input) {
        return applications.mutate(id, "changeActualEndTime", input, false, (row, ctx) -> {
            require(input.getActualEndTime() >= row.getDetails().getStartTime(), "invalid_dates");
            row.setActualEndTime(input.getActualEndTime()); row.setActiveHouseKey(null); row.setStatus("finished");
            return Mono.empty();
        });
    }
    public Mono<DecorationApplicationEntity> end(String id, DecorationRequests.Action input) {
        return applications.mutate(id, "end", input, false, (row, ctx) -> {
            active(row); require(input.getRemark() != null && !input.getRemark().isBlank(), "remark_required");
            row.setActualEndTime(System.currentTimeMillis()); row.setEndReason(input.getRemark()); row.setActiveHouseKey(null); row.setStatus("finished"); return Mono.empty();
        });
    }
}

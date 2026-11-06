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
                amount(input.getAmount()); row.setDepositReviewStatus(input.getResult()); row.setApprovedDeposit(input.getAmount());
                row.setDepositReviewerId(ctx.userId()); row.setDepositReviewerName(ctx.userName()); row.setDepositReviewTime(System.currentTimeMillis()); row.setDepositReviewRemark(input.getRemark());
            } else {
                var material = row.getDetails().getMaterials().stream().filter(m -> m.getDefinition().getId().equals(input.getItemId())).findFirst().orElseThrow(() -> error("invalid_materials", 400));
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

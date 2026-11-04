package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.jetlinks.pro.smartpark.repair.api.RepairActionRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairReviewEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 有偿签字后的完成表单沿用签字前提交内容。只在已通过工单权限检查的动作事务内调用，
 * 不接受客户端改写签字所对应的完成情况、回复语或附件；返工或归档驳回后恢复编辑。
 * 复用过程与审核记录，无额外缓存或常驻状态。
 */
@Service
@AllArgsConstructor
public class RepairCompletionService {
    private final RepairProcessService processes;
    private final RepairReviewService reviews;

    public Mono<Boolean> restoreSignedFinish(RepairOrderEntity order, String action, RepairActionRequest input) {
        if (!"finish".equals(action) || !Boolean.TRUE.equals(order.getPaid()) || !Boolean.TRUE.equals(order.getSigned())) {
            return Mono.just(false);
        }
        Mono<Boolean> rework = processes.createQuery()
            .where(RepairProcessEntity::getOrderId, order.getId()).and(RepairProcessEntity::getAction, "callback")
            .fetch().any(RepairCompletionService::isRework);
        Mono<Boolean> rejected = reviews.createQuery()
            .where(RepairReviewEntity::getOrderId, order.getId()).and(RepairReviewEntity::getKind, "archive")
            .and(RepairReviewEntity::getStatus, 3).count().map(count -> count > 0);
        return Mono.zip(rework, rejected).flatMap(reopened -> {
            if (reopened.getT1() || reopened.getT2()) return Mono.just(false);
            return processes.createQuery()
                .where(RepairProcessEntity::getOrderId, order.getId()).and(RepairProcessEntity::getAction, "quote")
                .orderBy(SortOrder.desc(RepairProcessEntity::getOrderVersion), SortOrder.desc(RepairProcessEntity::getId))
                .fetchOne().doOnNext(quote -> restore(input, quote, order))
                .map(quote -> true).defaultIfEmpty(false);
        });
    }

    static boolean isRework(RepairProcessEntity process) {
        return process.getDetails() != null && Boolean.TRUE.equals(process.getDetails().get("rework"));
    }

    static void restore(RepairActionRequest input, RepairProcessEntity quote, RepairOrderEntity order) {
        input.setDescription(quote.getDescription());
        input.setAttachmentIds(quote.getAttachmentIds());
        var details = quote.getDetails();
        input.setReply(details == null ? null : (String) details.get("reply"));
        input.setReplyId(details == null ? null : (String) details.get("replyId"));
        input.setMaterialAmount(order.getMaterialAmount());
        input.setArtificialAmount(order.getArtificialAmount());
    }
}

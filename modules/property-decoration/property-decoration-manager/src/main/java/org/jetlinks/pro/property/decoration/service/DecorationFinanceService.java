package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.model.DecorationFinanceGateway;
import org.jetlinks.pro.property.decoration.integration.SimulatedDecorationGateway;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationFinanceReceiptEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.Objects;
import java.math.BigDecimal;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import java.util.Map;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 统一核验真实或显式启用的模拟提供方回执；无提供方明确503，不直接改写资金成功状态。 */
@Service
@RequiredArgsConstructor
public class DecorationFinanceService {
    private final ObjectProvider<DecorationFinanceGateway> gateways;
    private final DecorationApplicationService applications;
    private final DecorationFinanceReceiptRepository receipts;
    private final ObjectProvider<org.jetlinks.pro.property.decoration.model.DecorationNotificationGateway> notifications;
    public Map<String, Boolean> capabilities() {
        boolean configured = gateways.getIfAvailable() != null;
        return Map.of("payment", configured, "refund", configured, "pushNotification", notifications.getIfAvailable() != null,
            "simulated", gateways.getIfAvailable() instanceof SimulatedDecorationGateway);
    }
    public Mono<DecorationApplicationEntity> process(String id, DecorationRequests.Finance input, boolean refund, boolean mine) {
        return process(id, input, refund, mine, false);
    }
    /** 测试按钮的独立入口：只接受模拟提供方，仍校验本人/项目、状态、金额与幂等回执。 */
    public Mono<DecorationApplicationEntity> simulate(String id, DecorationRequests.Action input, boolean refund, boolean mine) {
        return Mono.defer(() -> {
            if (!(gateways.getIfAvailable() instanceof SimulatedDecorationGateway)) {
                return Mono.error(error("simulation_unavailable", 503));
            }
            var finance = new DecorationRequests.Finance();
            finance.setRequestId(input.getRequestId()); finance.setVersion(input.getVersion()); finance.setRemark(input.getRemark());
            return process(id, finance, refund, mine, true);
        });
    }
    private Mono<DecorationApplicationEntity> process(String id, DecorationRequests.Finance input, boolean refund, boolean mine, boolean simulated) {
        String action = (simulated ? "simulation/" : "") + (refund ? "refund" : "payment");
        return applications.mutate(id, action, input, mine, (row, ctx) -> {
            active(row);
            RefundPlan plan = refund ? refundPlan(row) : null;
            if (!refund) {
                require("pending_payment".equals(row.getStatus()) && row.getDepositReviewStatus() == 1, "review_required");
                amount(row.getApprovedDeposit());
            }
            boolean offline = "pc_admin".equals(row.getSource());
            // 模拟单据由服务端生成，客户端不能借测试入口提交真实单据；真实线下核验规则保持不变。
            String reference = simulated ? "SIM-" + (refund ? "REFUND-" : "PAYMENT-")
                + DecorationApplicationService.identity(id + (refund ? ":" + plan.stage() : "")) : input.getReference();
            require(simulated || !offline || !mine && reference != null && !reference.isBlank(), "finance_reference_required");
            // 零金额末期只结束流程，不伪造外部退款凭据。
            if (refund && plan.amount().signum() == 0) {
                finishRefund(row, plan, null, System.currentTimeMillis());
                return Mono.empty();
            }
            var gateway = gateways.getIfAvailable();
            if (gateway == null) return Mono.error(error("finance_unavailable", 503));
            var request = new DecorationFinanceGateway.Request(DecorationApplicationService.identity(id + (refund ? ":refund:" + plan.stage() : ":payment")), id,
                ctx.applicationId(), ctx.tenantId(), row.getProjectId(), row.getSpaceId(), row.getOwnerUserId(), row.getDetails().getAgreement().getChargeItemId(),
                refund ? plan.amount() : row.getApprovedDeposit(), reference, offline);
            return (refund ? gateway.refund(request) : gateway.payment(request)).switchIfEmpty(Mono.error(error("finance_unavailable", 503)))
                .flatMap(result -> apply(row, request, result, refund, plan));
        });
    }
    private Mono<Void> apply(DecorationApplicationEntity row, DecorationFinanceGateway.Request request, DecorationFinanceGateway.Result result, boolean refund, RefundPlan plan) {
        require(Objects.equals(result.decorationId(), request.decorationId()) && Objects.equals(result.applicationId(), request.applicationId())
            && Objects.equals(result.tenantId(), request.tenantId()) && Objects.equals(result.projectId(), request.projectId())
            && Objects.equals(result.spaceId(), request.spaceId()) && Objects.equals(result.ownerUserId(), request.ownerUserId())
            && Objects.equals(result.chargeItemId(), request.chargeItemId()) && result.amount() != null && result.amount().compareTo(request.amount()) == 0, "finance_mismatch");
        require(result.direction() == (refund ? DecorationFinanceGateway.Direction.REFUND : DecorationFinanceGateway.Direction.PAYMENT)
            && result.offline() == request.offline(), "finance_mismatch");
        require(result.reference() != null && result.provider() != null && result.status() != null, "finance_mismatch");
        if (request.offline()) require(result.reference().equals(request.reference()) && result.status() == DecorationFinanceGateway.Status.SUCCEEDED, "finance_mismatch");
        if (result.status() == DecorationFinanceGateway.Status.FAILED) return Mono.error(error("finance_failed", 409));
        if (result.status() == DecorationFinanceGateway.Status.PENDING) {
            if (refund) row.setRefundStatus(1); else row.setPaymentStatus(1);
            return Mono.empty();
        }
        require(result.settledTime() != null, "finance_mismatch");
        var receipt = new DecorationFinanceReceiptEntity();
        receipt.setId(DecorationApplicationService.identity(result.provider() + ":" + result.reference())); receipt.setReceiptKey(receipt.getId());
        receipt.setDecorationId(row.getId()); receipt.setDirection(refund ? "refund" : "payment"); receipt.setProvider(result.provider()); receipt.setReference(result.reference()); receipt.setCreateTime(System.currentTimeMillis());
        return receipts.insert(receipt).doOnSuccess(ignore -> {
            if (refund) {
                finishRefund(row, plan, result.reference(), result.settledTime());
            } else {
                row.setPaymentStatus(2); row.setPaymentReference(result.reference()); row.setPaidAmount(result.amount()); row.setPaidTime(result.settledTime()); row.setStatus("pending_permit");
            }
        }).then();
    }
    record RefundPlan(String stage, BigDecimal amount, boolean last) {}
    /** 二验通过后按冻结协议一次性结算；历史分期配置合并总额，并扣除已核验退款。 */
    static RefundPlan refundPlan(DecorationApplicationEntity row) {
        require(row.getPaymentStatus() == 2 && row.getRefundStatus() != 2, "refund_not_allowed");
        require("acceptance_passed".equals(row.getStatus()), "refund_not_allowed");
        var agreement = row.getDetails().getAgreement();
        BigDecimal total = agreement.getRefundAmount();
        if (total == null) {
            total = agreement.getFirstRefund() == null && agreement.getSecondRefund() == null
                ? row.getPaidAmount()
                : (agreement.getFirstRefund() == null ? BigDecimal.ZERO : agreement.getFirstRefund())
                    .add(agreement.getSecondRefund() == null ? BigDecimal.ZERO : agreement.getSecondRefund());
        }
        BigDecimal refunded = row.getRefundedAmount() == null ? BigDecimal.ZERO : row.getRefundedAmount();
        require(total.signum() >= 0 && total.compareTo(row.getPaidAmount()) <= 0 && refunded.signum() >= 0 && refunded.compareTo(total) <= 0, "invalid_refund_amount");
        return new RefundPlan("single", total.subtract(refunded), true);
    }
    private static void finishRefund(DecorationApplicationEntity row, RefundPlan plan, String reference, long time) {
        BigDecimal refunded = row.getRefundedAmount() == null ? BigDecimal.ZERO : row.getRefundedAmount();
        require(refunded.add(plan.amount()).compareTo(row.getPaidAmount()) <= 0, "invalid_refund_amount");
        var stage = new DecorationModels.RefundStage();
        stage.setStage(plan.stage()); stage.setAmount(plan.amount()); stage.setReference(reference); stage.setSettledTime(time);
        row.getDetails().getRefunds().add(stage);
        row.setRefundedAmount(refunded.add(plan.amount())); row.setRefundReference(reference); row.setRefundTime(time);
        row.setRefundStatus(plan.last() ? 2 : 0);
        if (plan.last()) {
            row.setActualEndTime(time); row.setActiveHouseKey(null); row.setStatus("finished");
        }
    }

}

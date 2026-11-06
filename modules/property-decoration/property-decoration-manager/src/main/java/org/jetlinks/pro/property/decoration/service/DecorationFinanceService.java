package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.model.DecorationFinanceGateway;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.entity.DecorationFinanceReceiptEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.Objects;
import java.util.Map;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 外部资金结果的唯一核验方；无适配器明确503，不能用本地按钮造收退款成功。 */
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
            "simulated", gateways.getIfAvailable() instanceof org.jetlinks.pro.property.decoration.integration.SimulatedDecorationGateway);
    }
    public Mono<DecorationApplicationEntity> process(String id, DecorationRequests.Finance input, boolean refund, boolean mine) {
        return applications.mutate(id, refund ? "refund" : "payment", input, mine, (row, ctx) -> {
            active(row);
            if (refund) {
                require("acceptance_passed".equals(row.getStatus()) && row.getPaymentStatus() == 2 && row.getRefundStatus() != 2, "refund_not_allowed");
            } else {
                require("pending_payment".equals(row.getStatus()) && row.getDepositReviewStatus() == 1, "review_required");
                amount(row.getApprovedDeposit());
            }
            boolean offline = "pc_admin".equals(row.getSource());
            require(!offline || !mine && input.getReference() != null && !input.getReference().isBlank(), "finance_reference_required");
            var gateway = gateways.getIfAvailable();
            if (gateway == null) return Mono.error(error("finance_unavailable", 503));
            var request = new DecorationFinanceGateway.Request(DecorationApplicationService.identity(id + (refund ? ":refund" : ":payment")), id,
                ctx.applicationId(), ctx.tenantId(), row.getProjectId(), row.getSpaceId(), row.getOwnerUserId(), row.getDetails().getAgreement().getChargeItemId(),
                refund ? row.getPaidAmount() : row.getApprovedDeposit(), input.getReference(), offline);
            return (refund ? gateway.refund(request) : gateway.payment(request)).switchIfEmpty(Mono.error(error("finance_unavailable", 503)))
                .flatMap(result -> apply(row, request, result, refund));
        });
    }
    private Mono<Void> apply(DecorationApplicationEntity row, DecorationFinanceGateway.Request request, DecorationFinanceGateway.Result result, boolean refund) {
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
                row.setRefundStatus(2); row.setRefundReference(result.reference()); row.setRefundedAmount(result.amount()); row.setRefundTime(result.settledTime());
                row.setActualEndTime(result.settledTime()); row.setActiveHouseKey(null); row.setStatus("finished");
            } else {
                row.setPaymentStatus(2); row.setPaymentReference(result.reference()); row.setPaidAmount(result.amount()); row.setPaidTime(result.settledTime()); row.setStatus("pending_permit");
            }
        }).then();
    }
}

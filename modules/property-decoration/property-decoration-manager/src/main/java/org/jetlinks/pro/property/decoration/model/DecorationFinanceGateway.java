package org.jetlinks.pro.property.decoration.model;

import java.math.BigDecimal;
import reactor.core.publisher.Mono;

/**
 * 待接入财务/支付的公开边界。实现必须查询真实服务，不接受客户端“成功”声明。
 * requestKey 应作为外部订单幂等键；超时不得改成成功或盲目创建新订单。
 * 实现须保留调用者认证、应用与资产上下文，并使用非阻塞传输；不能注入其他业务manager。
 */
public interface DecorationFinanceGateway {
    record Request(String requestKey, String decorationId, String applicationId, String tenantId,
        String projectId, String spaceId, String ownerUserId, String chargeItemId,
        BigDecimal amount, String reference, boolean offline) {}
    record Result(String decorationId, String applicationId, String tenantId, String projectId,
        String spaceId, String ownerUserId, String chargeItemId, BigDecimal amount,
        String provider, String reference, Direction direction, boolean offline, Status status, Long settledTime) {}
    enum Direction { PAYMENT, REFUND }
    enum Status { PENDING, SUCCEEDED, FAILED }
    Mono<Result> payment(Request request);
    Mono<Result> refund(Request request);
}

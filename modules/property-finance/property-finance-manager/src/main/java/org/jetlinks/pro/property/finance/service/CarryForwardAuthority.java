package org.jetlinks.pro.property.finance.service;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.property.finance.entity.CarryForwardPlanEntity;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** 自动结转的委托权限策略；scope/token恢复使用共同的金融任务授权边界。 */
final class CarryForwardAuthority {
    private static final String ERROR = "carry_authority_invalid";

    private CarryForwardAuthority() {}

    static Mono<String> capture(Authentication auth) {
        return FinanceJobAuthority.capture(auth, ERROR);
    }

    static Mono<Context> restore(CarryForwardPlanEntity plan, Authentication current) {
        return FinanceJobAuthority.restore(
                plan.getExecutorSnapshot(),
                plan.getTenantId(),
                current,
                auth ->
                        auth.hasPermission("property-finance-carry", "save")
                                || auth.hasPermission("property-finance-carry", "state"),
                ERROR);
    }

    static String executorId(CarryForwardPlanEntity plan) {
        return FinanceJobAuthority.executorId(plan.getExecutorSnapshot(), ERROR);
    }
}

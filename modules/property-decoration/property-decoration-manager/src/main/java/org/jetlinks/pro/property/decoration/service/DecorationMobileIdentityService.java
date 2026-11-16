package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.exception.BusinessException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 身份由当前应用权限与基础档案共同判定；端别选择不签发或扩大任何权限。 */
@Service @RequiredArgsConstructor
public class DecorationMobileIdentityService {
    private final DecorationScope scope;
    public record Identity(boolean owner, boolean staff) {}

    public Mono<Identity> current() {
        return scope.current().flatMap(ctx -> {
            Mono<Boolean> owner = ctx.authentication().hasPermission("decoration-owner", "query")
                ? scope.ownerHouses().map(houses -> !houses.isEmpty()) : Mono.just(false);
            Mono<Boolean> staff = ctx.authentication().hasPermission("decoration-task", "query")
                ? scope.employee().hasElement().onErrorResume(BusinessException.class, error ->
                    "error.decoration.employee_required".equals(error.getMessage()) ? Mono.just(false) : Mono.error(error))
                : Mono.just(false);
            // 目录故障必须报错，不能降级为无身份或扩大为双身份。
            return Mono.zip(owner, staff).map(pair -> new Identity(pair.getT1(), pair.getT2()));
        });
    }
}

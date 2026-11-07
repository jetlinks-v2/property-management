package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.activity.PrepayQuoteInput;
import org.jetlinks.pro.property.finance.service.BillingJobProperties;
import org.jetlinks.pro.property.finance.service.PrepayQuoteService;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 复用柜台收款按钮权限；客户端不能指定业务日、金额、报价有效期或审批结果。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "property-finance-cashier", name = "收银台")
@RequestMapping("/property/finance/projects/{projectId}/cashier/prepay/quotes")
public class PrepayQuoteController {
    private final PrepayQuoteService service;
    private final BillingJobProperties properties;

    @PostMapping
    @ResourceAction(id = "collect", name = "收款")
    public Mono<PrepayQuoteService.View> create(
            @PathVariable String projectId, @RequestBody PrepayQuoteInput input) {
        return service.create(projectId, input, LocalDate.now(ZoneId.of(properties.getZone())));
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<PrepayQuoteService.View> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }
}

package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.activity.PrepayConfirmationInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 仅柜台人工确认付款，不开放APP、线上成功回调或收银台开票。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "property-finance-cashier", name = "收银台")
@RequestMapping("/property/finance/projects/{projectId}/cashier/prepay/quotes")
public class PrepayConfirmationController {
    private final PrepayConfirmationService service;
    private final BillingJobProperties properties;

    @PostMapping("/{id}/_confirm")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<PrepayConfirmationService.Detail> confirm(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody PrepayConfirmationInput input) {
        return service.confirm(
                projectId, id, input, LocalDate.now(ZoneId.of(properties.getZone())));
    }
}

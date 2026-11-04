package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;

/** 柜台按房屋查看预缴候选，复用collect而非活动/设置维护权限。 */
@RestController
@RequiredArgsConstructor
@Authorize
@Resource(id = "property-finance-cashier", name = "收银台")
@RequestMapping("/property/finance/projects/{projectId}/cashier/prepay")
public class PrepayOptionsController {
    private final PrepayOptionsService service;
    private final BillingJobProperties properties;

    @GetMapping("/_options")
    @ResourceAction(id = "collect", name = "收款")
    public Mono<List<PrepayOptionsService.Setting>> options(
            @PathVariable String projectId, @RequestParam String spaceId) {
        return service.options(projectId, spaceId, LocalDate.now(ZoneId.of(properties.getZone())));
    }
}

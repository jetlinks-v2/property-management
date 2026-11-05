package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.payment.FundReturnInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/funds/offset-returns")
@Resource(id = "property-finance-funds", name = "预收与押金")
public class FundReturnController {
    private final FundReturnService service;
    private final BillingJobProperties properties;

    @PostMapping("/_preview")
    @ResourceAction(id = "return-offset", name = "退回冲抵余额")
    public Mono<FundReturnService.Preview> preview(
            @PathVariable String projectId, @RequestBody FundReturnInput input) {
        return service.preview(projectId, input, date());
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "return-offset", name = "退回冲抵余额")
    public Mono<FundsService.Result> confirm(
            @PathVariable String projectId, @RequestBody FundReturnService.Submission input) {
        return service.confirm(projectId, input, date());
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }
}

package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.payment.FundRevokeInput;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/funds/topup-revocations")
@Resource(id = "property-finance-funds", name = "预收与押金")
public class FundRevokeController {
    private final FundRevokeService service;
    private final BillingJobProperties properties;

    @PostMapping("/_preview")
    @ResourceAction(id = "revoke-topup", name = "撤销预收存入")
    public Mono<FundRevokeService.Preview> preview(
            @PathVariable String projectId, @RequestBody FundRevokeInput input) {
        return service.preview(projectId, input, date());
    }

    @PostMapping("/_confirm")
    @ResourceAction(id = "revoke-topup", name = "撤销预收存入")
    public Mono<FundsService.Result> confirm(
            @PathVariable String projectId, @RequestBody FundRevokeService.Submission input) {
        return service.confirm(projectId, input, date());
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }
}

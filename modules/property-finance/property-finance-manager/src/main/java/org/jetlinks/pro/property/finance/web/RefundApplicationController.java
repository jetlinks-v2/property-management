package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.RefundApplicationEntity;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;

/** 退款申请独立于资金事实；审核权限不由原退款发起按钮授予。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/refund-applications")
@Resource(id = "property-finance-refund", name = "退款管理")
public class RefundApplicationController {
    private final RefundApplicationService service;
    private final BillingJobProperties properties;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<RefundApplicationService.View>> query(
            @PathVariable String projectId, @RequestBody QueryParamEntity input) {
        return service.query(projectId, input);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<RefundApplicationService.Detail> detail(
            @PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping("/{id}/_edit")
    @ResourceAction(id = "save", name = "修改退款申请")
    public Mono<RefundApplicationEntity> edit(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody RefundApplicationService.Edit input) {
        return service.edit(projectId, id, input, date());
    }

    @GetMapping("/{id}/logs")
    @ResourceAction(id = "query", name = "查询")
    public Mono<
                    java.util.List<
                            org.jetlinks.pro.property.finance.entity
                                    .RefundApplicationOperationEntity>>
            logs(@PathVariable String projectId, @PathVariable String id) {
        return service.logs(projectId, id);
    }

    @PostMapping("/{id}/_approve")
    @ResourceAction(id = "approve", name = "确认退款")
    public Mono<RefundApplicationEntity> approve(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody RefundApplicationService.Action input) {
        return service.act(projectId, id, "APPROVE", input, date());
    }

    @PostMapping("/{id}/_reject")
    @ResourceAction(id = "reject", name = "审核不通过")
    public Mono<RefundApplicationEntity> reject(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody RefundApplicationService.Action input) {
        return service.act(projectId, id, "REJECT", input, date());
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }
}

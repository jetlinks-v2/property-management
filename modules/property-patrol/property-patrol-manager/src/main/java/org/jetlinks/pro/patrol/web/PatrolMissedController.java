package org.jetlinks.pro.patrol.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity;
import org.jetlinks.pro.patrol.entity.PatrolMissedEventEntity;
import org.jetlinks.pro.patrol.service.PatrolMissedService;
import org.jetlinks.pro.patrol.service.PatrolMissedService.Decision;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.List;

/** PC 漏检归属巡更记录权限；服务统一拥有计划资产检查和处理事务。 */
@RestController
@Validated
@AllArgsConstructor
@RequestMapping("/park/patrol/missed")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolMissedController {
    private final PatrolMissedService service;
    public record GroupRequest(@NotBlank String taskGroupId) {}
    public record ApplyRequest(@NotEmpty @Size(max = 100) List<@NotBlank String> itemIds,
                               @NotBlank @Size(max = 500) String reason) {}
    public record AuditRequest(@NotEmpty @Size(max = 100) @Valid List<@NotNull Decision> items,
                               boolean approved, @Size(max = 500) String remark) {}
    public record WithdrawRequest(@NotBlank String itemId, @NotBlank String applicationId) {}
    @PostMapping("/group") @QueryAction
    public Mono<List<PatrolMissedItemEntity>> group(@RequestBody @Valid GroupRequest request) {
        return service.group(request.taskGroupId());
    }
    @PostMapping("/apply") @ResourceAction(id = "applyMissed", name = "申报漏检")
    public Mono<Integer> apply(@RequestBody @Valid ApplyRequest request) {
        return service.apply(request.itemIds(), request.reason(), false);
    }
    @PostMapping("/audit") @ResourceAction(id = "auditMissed", name = "审核漏检")
    public Mono<Integer> audit(@RequestBody @Valid AuditRequest request) {
        return service.audit(request.items(), request.approved(), request.remark());
    }
    @PostMapping("/withdraw") @ResourceAction(id = "applyMissed", name = "申报漏检")
    public Mono<Integer> withdraw(@RequestBody @Valid WithdrawRequest request) {
        return service.withdraw(request.itemId(), request.applicationId(), false);
    }
    @GetMapping("/{id}/history") @QueryAction
    public Flux<PatrolMissedEventEntity> history(@PathVariable String id) { return service.history(id, false); }
}

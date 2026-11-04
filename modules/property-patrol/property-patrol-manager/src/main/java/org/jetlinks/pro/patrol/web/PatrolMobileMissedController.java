package org.jetlinks.pro.patrol.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.patrol.entity.PatrolMissedItemEntity;
import org.jetlinks.pro.patrol.entity.PatrolMissedEventEntity;
import org.jetlinks.pro.patrol.service.PatrolMissedService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.time.LocalDate;
import java.util.List;

/** H5 不接受客户端指定申报人或岗位；本人资格由服务端检查。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/mobile/missed")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolMobileMissedController {
    private final PatrolMissedService service;
    public record Range(@NotNull LocalDate from, @NotNull LocalDate to) {}
    @PostMapping("/_query") @QueryAction
    public Mono<List<PatrolMissedItemEntity>> query(@RequestBody @Valid Range request) {
        return service.mine(request.from(), request.to());
    }
    @PostMapping("/apply") @ResourceAction(id = "submitMissed", name = "申报漏检")
    public Mono<Integer> apply(@RequestBody @Valid PatrolMissedController.ApplyRequest request) {
        return service.apply(request.itemIds(), request.reason(), true);
    }
    @PostMapping("/withdraw") @ResourceAction(id = "submitMissed", name = "申报漏检")
    public Mono<Integer> withdraw(@RequestBody @Valid PatrolMissedController.WithdrawRequest request) {
        return service.withdraw(request.itemId(), request.applicationId(), true);
    }
    @GetMapping("/{id}/history") @QueryAction
    public Flux<PatrolMissedEventEntity> history(@PathVariable String id) { return service.history(id, true); }
}

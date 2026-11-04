package org.jetlinks.pro.patrol.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.pro.assets.annotation.AssetsController;
import org.jetlinks.pro.patrol.service.PatrolPointAssignmentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.util.List;

/** 点位岗位和规则的批量入口；服务负责点位与计划的资产授权及事务。 */
@RestController
@RequestMapping("/park/patrol/point-assignments")
@Resource(id = "patrol-plan", name = "巡更计划")
@AssetsController(ignore = true)
@AllArgsConstructor
public class PatrolPointAssignmentController {
    private final PatrolPointAssignmentService service;
    public record Selection(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds) {}
    public record Posts(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
                        @NotEmpty @Size(max = 20) List<@NotBlank String> postIds) {}
    public record Rule(@NotBlank String postId, @NotBlank String levelId, @NotBlank String standardId) {}
    public record Rules(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
                        @NotEmpty @Size(max = 20) List<@NotNull @Valid Rule> rules) {}
    @PostMapping("/_detail")
    @QueryAction
    public Mono<List<PatrolPointAssignmentService.Detail>> detail(@RequestBody @Valid Selection value) {
        return service.detail(value.pointIds());
    }
    @PostMapping("/posts")
    @SaveAction
    public Mono<Void> assign(@RequestBody @Valid Posts value) {
        return service.assign(value.pointIds(), value.postIds());
    }
    @PostMapping("/rules")
    @SaveAction
    public Mono<Void> configure(@RequestBody @Valid Rules value) {
        return service.configure(value.pointIds(), value.rules().stream().map(rule ->
                new PatrolPointAssignmentService.Rule(rule.postId(), rule.levelId(), rule.standardId())).toList());
    }
}

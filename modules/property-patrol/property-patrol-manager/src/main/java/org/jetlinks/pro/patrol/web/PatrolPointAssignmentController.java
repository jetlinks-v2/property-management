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
    public record Selection(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds, @Size(max = 64) String categoryId) {}
    public record Posts(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
                        @NotEmpty @Size(max = 20) List<@NotBlank String> roleIds, @Size(max = 64) String categoryId) {}
    public record Rule(@NotBlank String roleId, @NotBlank String levelId, @NotBlank String standardId) {}
    public record Rules(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
                        @NotEmpty @Size(max = 20) List<@NotNull @Valid Rule> rules, @Size(max = 64) String categoryId) {}
    /** 未配置规则的角色允许保存；等级和标准须成对填写，由事务服务校验。 */
    public record ConfigurationRule(@NotNull @Size(max = 64) String roleId, @Size(max = 64) String levelId,
                                    @Size(max = 64) String standardId) {}
    public record Configuration(@NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
                                @NotNull @Size(max = 21) List<@NotNull @Valid ConfigurationRule> rules,
                                @Size(max = 64) String categoryId) {}
    @PostMapping("/_summary")
    @QueryAction
    public Mono<List<PatrolPointAssignmentService.Detail>> summary(@RequestBody @Valid Selection value) {
        return service.summary(value.pointIds());
    }
    @PostMapping("/_detail")
    @QueryAction
    public Mono<List<PatrolPointAssignmentService.Detail>> detail(@RequestBody @Valid Selection value) {
        return service.detail(value.pointIds(), value.categoryId());
    }
    @PostMapping("/roles")
    @SaveAction
    public Mono<Void> assign(@RequestBody @Valid Posts value) {
        return service.assign(value.pointIds(), value.roleIds(), value.categoryId());
    }
    @PostMapping("/rules")
    @SaveAction
    public Mono<Void> configure(@RequestBody @Valid Rules value) {
        return service.configure(value.pointIds(), value.rules().stream().map(rule ->
                new PatrolPointAssignmentService.Rule(rule.roleId(), rule.levelId(), rule.standardId())).toList(), value.categoryId());
    }

    /** 编辑器统一保存角色和规则，失败整体回滚；权限与分步保存相同。 */
    @PostMapping("/configuration")
    @SaveAction
    public Mono<Void> configureAll(@RequestBody @Valid Configuration value) {
        return service.configureAll(value.pointIds(), value.rules().stream().map(rule ->
                new PatrolPointAssignmentService.Rule(rule.roleId(), rule.levelId(), rule.standardId())).toList(), value.categoryId());
    }
}

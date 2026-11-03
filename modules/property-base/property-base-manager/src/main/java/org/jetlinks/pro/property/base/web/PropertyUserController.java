package org.jetlinks.pro.property.base.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.service.PropertyUserService;
import org.jetlinks.pro.property.base.service.UserSyncJob;
import org.jetlinks.pro.property.base.service.UserSyncService;
import org.jetlinks.pro.property.base.user.UserIdentity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/** 受控镜像接口，不继承通用 CRUD，避免伪造请求改 SaaS 资料或物理删除。 */
@RestController
@RequestMapping("/property/user")
@Resource(id = "property-user", name = "物业用户管理")
@Authorize
@Tag(name = "物业用户管理")
@RequiredArgsConstructor
public class PropertyUserController {
    private final PropertyUserService users;
    private final UserSyncJob job;
    private final UserSyncService sync;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "分页查询当前用户资产范围内的物业用户")
    public Mono<PagerResult<PropertyUserEntity>> query(@RequestBody QueryParamEntity query) {
        return users.query(query);
    }

    @PostMapping("/_counts")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "按筛选及资产权限统计身份数量，双身份分别计入两类")
    public Mono<PropertyUserService.Counts> counts(@RequestBody QueryParamEntity query) {
        return users.counts(query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "查看用户只读资料及物业身份")
    public Mono<PropertyUserEntity> detail(@PathVariable String id) {
        return users.detail(id);
    }

    @GetMapping("/_identities")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "获取物业身份选项")
    public Flux<UserIdentity> identities() {
        return Flux.fromArray(UserIdentity.values());
    }

    @PutMapping("/{id}/identities")
    @ResourceAction(id = "save", name = "标注身份")
    @Operation(summary = "版本校验后保存完整身份集合，空集合表示清空")
    public Mono<PropertyUserEntity> identities(
            @PathVariable String id, @Valid @RequestBody IdentityUpdate request) {
        return users.updateIdentities(id, request.identities(), request.version());
    }

    @PostMapping("/_sync")
    @ResourceAction(id = "sync", name = "同步用户")
    @Operation(summary = "提交固定 SaaS 用户来源的同步任务")
    public Mono<UserSyncService.Status> synchronize() {
        return job.submit();
    }

    @GetMapping("/_sync/status")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "查询最近同步任务及上次成功时间")
    public Mono<UserSyncService.Status> status() {
        return sync.status();
    }

    public record IdentityUpdate(
            @NotNull Set<@NotNull UserIdentity> identities, @NotNull @Min(0) Long version) {}
}

package org.jetlinks.pro.property.base.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.OrganizationSyncResult;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.service.OrganizationSyncService;
import org.jetlinks.pro.property.base.service.PropertyOrganizationService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.List;

/** 基础模块的受控组织接口；所有业务前端共用，不暴露通用组织编辑/物理删除。 */
@RestController
@RequestMapping("/property/organization")
@Resource(id = "property-organization", name = "物业组织类型配置")
@Authorize
@RequiredArgsConstructor
@Tag(name = "物业组织类型配置")
public class PropertyOrganizationController {
    private final PropertyOrganizationService organizations;
    private final OrganizationSyncService sync;

    @GetMapping("/_tree")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "获取有权访问的物业组织树")
    public Mono<List<PropertyOrganizationEntity>> tree() {
        return organizations.tree();
    }

    @GetMapping("/_types")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "获取可标注的物业组织类型")
    public Flux<OrganizationType> types() {
        return Flux.fromArray(OrganizationType.values());
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "查看组织详情")
    public Mono<PropertyOrganizationEntity> detail(@PathVariable String id) {
        return organizations.detail(id);
    }

    @PutMapping("/{id}/type")
    @ResourceAction(id = "save", name = "标注类型")
    @Operation(summary = "标注或清除当前组织类型")
    public Mono<PropertyOrganizationEntity> updateType(
            @PathVariable String id, @Valid @RequestBody TypeUpdate request) {
        return organizations.updateType(id, request.organizationType(), request.version());
    }

    @PostMapping("/_sync")
    @ResourceAction(id = "sync", name = "同步组织")
    @Operation(summary = "触发固定 SaaS 来源的全量组织同步")
    public Mono<OrganizationSyncResult> synchronize() {
        return sync.synchronize();
    }

    @GetMapping("/_sync/status")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "获取最近组织同步状态")
    public Mono<OrganizationSyncService.Status> status() {
        return sync.status();
    }

    /** organizationType 必须显式提供；null 表示清除，版本必须来自最近一次查询。 */
    public record TypeUpdate(
            @JsonProperty(required = true) OrganizationType organizationType,
            @NotNull @Min(0) Long version) {}
}

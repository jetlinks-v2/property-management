package org.jetlinks.pro.property.base.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.service.PropertySpaceService;
import org.jetlinks.pro.property.base.service.SpaceSyncService;
import org.jetlinks.pro.property.base.space.SpaceSyncResult;
import org.jetlinks.pro.property.base.space.SpaceSyncStatus;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;

/** 空间结构只读，唯一维护动作是组织关联；同步固定使用 SaaS 命令来源。 */
@RestController
@RequestMapping("/property/space")
@Resource(id = "property-space", name = "物业空间配置")
@Authorize
@RequiredArgsConstructor
public class PropertySpaceController {
    private final PropertySpaceService spaces;
    private final SpaceSyncService sync;

    @GetMapping("/_projects")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertySpaceService.Project>> projects() {
        return spaces.projects();
    }

    @GetMapping("/_roots")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertySpaceService.SpaceView>> roots() {
        return spaces.roots();
    }

    @GetMapping("/_tree")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertySpaceService.SpaceView>> tree(@RequestParam String projectId) {
        return spaces.tree(projectId);
    }

    @PutMapping("/{rootId}/organization")
    @ResourceAction(id = "save", name = "关联空间")
    public Mono<PropertySpaceService.SpaceView> bind(
            @PathVariable String rootId, @Valid @RequestBody BindingUpdate input) {
        return spaces.bind(rootId, input.organizationId(), input.version());
    }

    @GetMapping("/{rootId}/_tree")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertySpaceService.SpaceView>> rootTree(@PathVariable String rootId) {
        return spaces.rootTree(rootId);
    }

    @PostMapping("/_sync")
    @ResourceAction(id = "sync", name = "同步空间")
    public Mono<SpaceSyncResult> synchronize() {
        return sync.synchronize();
    }

    @GetMapping("/_sync/status")
    @ResourceAction(id = "query", name = "查询")
    public Mono<SpaceSyncStatus> status() {
        return sync.status();
    }

    public record BindingUpdate(
            @com.fasterxml.jackson.annotation.JsonProperty(required = true) String organizationId,
            @NotNull @Min(0) Long version) {}
}

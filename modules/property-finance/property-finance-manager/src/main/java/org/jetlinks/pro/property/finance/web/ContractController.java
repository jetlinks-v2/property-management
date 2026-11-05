package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.contract.*;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 合同镜像只读和主动同步；不开放通用编辑或删除，服务内独立检查调用权限。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/projects/{projectId}/contracts")
@Resource(id = ContractMirrorService.RESOURCE, name = "合同资料")
public class ContractController {
    private final ContractMirrorService mirror;
    private final ContractSourceService sources;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<ContractMirrorView>> query(
            @PathVariable String projectId, @RequestBody Query input) {
        return mirror.query(
                projectId,
                input.keyword(),
                input.pageIndex() == null ? 0 : input.pageIndex(),
                input.pageSize() == null ? 12 : input.pageSize());
    }

    @PostMapping("/_sync")
    @ResourceAction(id = "sync", name = "同步合同")
    public Mono<ContractSyncResult> sync(@PathVariable String projectId, @RequestBody Sync input) {
        return sources.sync(projectId, input.startDate(), input.endDate());
    }

    public record Query(String keyword, Integer pageIndex, Integer pageSize) {}

    public record Sync(String startDate, String endDate) {}
}

package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.CollectionImportEntity;
import org.jetlinks.pro.property.finance.service.CollectionImportService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;

/** 收款导入使用既有财务资源权限；每个自定义端点均由服务验证项目与空间AssetsHolder。 */
@RestController
@RequiredArgsConstructor
@Authorize
@RequestMapping("/property/finance/projects/{projectId}/collection-imports/{kind}")
@Resource(id = "property-finance-collection-import", name = "收款导入")
public class CollectionImportController {
    private final CollectionImportService service;

    @GetMapping("/template.xlsx")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> template(
            @PathVariable String projectId,
            @PathVariable String kind,
            ServerHttpResponse response) {
        response.getHeaders()
                .set(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.getHeaders()
                .set("Content-Disposition", "attachment; filename=collection-import-template.xlsx");
        return response.writeWith(service.template(projectId, kind));
    }

    @PostMapping
    @ResourceAction(id = "import", name = "导入")
    public Mono<CollectionImportEntity> upload(
            @PathVariable String projectId,
            @PathVariable String kind,
            @RequestPart("file") FilePart file) {
        return service.importFile(projectId, kind, file);
    }

    @PostMapping("/_query")
    @ResourceAction(id = "imports", name = "导入记录")
    public Mono<PagerResult<CollectionImportEntity>> records(
            @PathVariable String projectId,
            @PathVariable String kind,
            @RequestBody QueryParamEntity query) {
        return service.records(projectId, kind, query);
    }

    @PostMapping("/{recordId}/lines/_query")
    @ResourceAction(id = "imports", name = "导入记录")
    public Mono<PagerResult<CollectionImportService.LineView>> lines(
            @PathVariable String projectId,
            @PathVariable String kind,
            @PathVariable String recordId,
            @RequestBody QueryParamEntity query) {
        return service.lines(projectId, kind, recordId, query);
    }

    @PutMapping("/{recordId}/lines/{lineId}")
    @ResourceAction(id = "import-edit", name = "编辑待审明细")
    public Mono<CollectionImportService.EditResult> edit(
            @PathVariable String projectId,
            @PathVariable String kind,
            @PathVariable String recordId,
            @PathVariable String lineId,
            @RequestBody CollectionImportService.Edit input) {
        return service.edit(projectId, kind, recordId, lineId, input);
    }

    @PostMapping("/{recordId}/_approve")
    @ResourceAction(id = "approve", name = "审核通过")
    public Mono<CollectionImportEntity> approve(
            @PathVariable String projectId,
            @PathVariable String kind,
            @PathVariable String recordId,
            @RequestBody CollectionImportService.Decision input) {
        return service.decide(projectId, kind, recordId, input, true);
    }

    @PostMapping("/{recordId}/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<CollectionImportEntity> reject(
            @PathVariable String projectId,
            @PathVariable String kind,
            @PathVariable String recordId,
            @RequestBody CollectionImportService.Decision input) {
        return service.decide(projectId, kind, recordId, input, false);
    }

    @PostMapping("/_approve")
    @ResourceAction(id = "approve", name = "审核通过")
    public Mono<List<CollectionImportEntity>> approveMany(
            @PathVariable String projectId,
            @PathVariable String kind,
            @RequestBody CollectionImportService.BulkDecision input) {
        return service.decideMany(projectId, kind, input, true);
    }

    @PostMapping("/_reject")
    @ResourceAction(id = "reject", name = "驳回")
    public Mono<List<CollectionImportEntity>> rejectMany(
            @PathVariable String projectId,
            @PathVariable String kind,
            @RequestBody CollectionImportService.BulkDecision input) {
        return service.decideMany(projectId, kind, input, false);
    }
}

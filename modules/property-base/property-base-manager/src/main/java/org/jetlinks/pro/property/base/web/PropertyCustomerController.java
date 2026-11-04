package org.jetlinks.pro.property.base.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.customer.CustomerRequests;
import org.jetlinks.pro.property.base.customer.CustomerView;
import org.jetlinks.pro.property.base.customer.RelationshipView;
import org.jetlinks.pro.property.base.excel.ArchiveImportSchema;
import org.jetlinks.pro.property.base.service.ArchiveExcelService;
import org.jetlinks.pro.property.base.service.PropertyCustomerService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 受控客户档案 API；服务按项目及空间资产校验，不暴露通用物理删除。 */
@RestController
@RequestMapping("/property/customer")
@Resource(id = "property-customer", name = "物业客户档案")
@Authorize
@Tag(name = "物业客户档案")
@RequiredArgsConstructor
public class PropertyCustomerController {
    private final PropertyCustomerService customers;
    private final ArchiveExcelService excel;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "按有权空间记录分页查询当前客户")
    public Mono<PagerResult<CustomerView>> query(
            @RequestParam String projectId,
            @RequestParam(required = false) String spaceId,
            @RequestBody QueryParamEntity query) {
        return customers.query(projectId, spaceId, query);
    }

    @PostMapping("/_profiles")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "选择已有客户，包含仅有历史空间记录的可读资料")
    public Mono<PagerResult<CustomerView>> profiles(
            @RequestParam String projectId, @RequestBody QueryParamEntity query) {
        return customers.profiles(projectId, query);
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "查看客户主档及可读空间内当前关系")
    public Mono<CustomerView> detail(@RequestParam String projectId, @PathVariable String id) {
        return customers.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    @Operation(summary = "为同步客户用户建档并关联已完善房屋")
    public Mono<CustomerView> create(
            @RequestParam String projectId, @RequestBody CustomerRequests.Create request) {
        return customers.create(projectId, request);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    @Operation(summary = "版本校验后更新本地客户资料")
    public Mono<CustomerView> update(
            @RequestParam String projectId,
            @PathVariable String id,
            @RequestBody CustomerRequests.Profile request) {
        return customers.update(projectId, id, request);
    }

    @PostMapping("/{id}/relationships")
    @ResourceAction(id = "save", name = "保存")
    @Operation(summary = "在当前空间新增已有客户")
    public Mono<RelationshipView> link(
            @RequestParam String projectId,
            @PathVariable String id,
            @RequestBody CustomerRequests.Link request) {
        return customers.link(projectId, id, request);
    }

    @DeleteMapping("/relationships/{id}")
    @ResourceAction(id = "delete", name = "删除")
    @Operation(summary = "从当前空间逻辑删除客户并自动记录结束时间，历史保留")
    public Mono<Void> deleteRelationship(
            @RequestParam String projectId, @PathVariable String id, @RequestParam long version) {
        return customers.deleteRelationship(projectId, id, version);
    }

    @PostMapping("/_history")
    @ResourceAction(id = "query", name = "查询")
    @Operation(summary = "查询每次已结束关联及完整客户主档")
    public Mono<PagerResult<RelationshipView>> history(
            @RequestParam String projectId,
            @RequestParam(required = false) String spaceId,
            @RequestBody QueryParamEntity query) {
        return customers.history(projectId, spaceId, query);
    }

    @GetMapping("/template.xlsx")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> template(
            @RequestParam String projectId,
            @RequestParam String customerType,
            ServerHttpResponse response) {
        return excel.template(projectId, ArchiveImportSchema.customerType(customerType))
                .flatMap(document -> excel.write(document, response));
    }

    @PostMapping(value = "/_import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourceAction(id = "import", name = "导入")
    public Mono<CustomerRequests.ImportResult> importFile(
            @RequestParam String projectId,
            @RequestParam String customerType,
            @RequestPart("file") FilePart file) {
        return excel.importCustomers(projectId, customerType, file);
    }

    @PostMapping("/_export")
    @ResourceAction(id = "export", name = "导出")
    public Mono<Void> export(
            @RequestParam String projectId,
            @RequestParam(required = false) String spaceId,
            @RequestParam(defaultValue = "false") boolean history,
            @RequestBody QueryParamEntity query,
            ServerHttpResponse response) {
        return excel.exportCustomers(projectId, spaceId, query, history)
                .flatMap(document -> excel.write(document, response));
    }
}

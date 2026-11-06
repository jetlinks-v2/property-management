package org.jetlinks.pro.property.decoration.web;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.jetlinks.pro.property.decoration.model.DecorationConfigurationImports;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/** 协议、资料、检查项、有效期和办理关系映射；维度不可通过编辑迁移。 */
@RestController @Authorize @RequiredArgsConstructor @Validated
@RequestMapping("/decoration/configurations") @Resource(id="decoration-configuration",name="装修基础配置")
public class DecorationConfigurationController {
    private final DecorationConfigurationService configurations;
    private final DecorationConfigurationImportService imports;
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<DecorationConfigurationEntity>> query(@RequestBody QueryParamEntity query) { return configurations.query(query); }
    @PostMapping @SaveAction
    public Mono<DecorationConfigurationEntity> save(@Valid @RequestBody DecorationRequests.Config input) { return configurations.save(input); }
    @DeleteMapping("/{id}") @DeleteAction
    public Mono<Void> delete(@PathVariable String id, @RequestParam long version) { return configurations.delete(id, version); }
    /** 通用空白模板不含业务资料；下载及导入复用配置保存权限。 */
    @GetMapping("/checks/import-template") @SaveAction
    public Mono<ResponseEntity<byte[]>> importTemplate() {
        return imports.template().map(bytes -> ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header("Content-Disposition", "attachment; filename=decoration-check-import.xlsx").body(bytes));
    }
    @PostMapping(value="/checks/import", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @SaveAction
    public Mono<DecorationConfigurationImports.Result> importChecks(
        @RequestParam @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=64) String requestId,
        @RequestPart("file") FilePart file) { return imports.importChecks(requestId, file); }
    /** 结果核对不会重做上传、导入或目录写入，仅原提交者且仍有项目权限可读。 */
    @GetMapping("/checks/import-result/{requestId}") @SaveAction
    public Mono<DecorationConfigurationImports.Result> importResult(
        @PathVariable @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=64) String requestId) {
        return imports.result(requestId);
    }
}

package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.finance.entity.TaxItemEntity;
import org.jetlinks.pro.property.finance.service.FormulaVariableService;
import org.jetlinks.pro.property.finance.service.StandardService;
import org.jetlinks.pro.property.finance.standard.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Map;

/** 收费标准菜单统一授权其科目/税目选择与公式校验，不要求额外的主档菜单权限。 */
@RestController
@RequestMapping("/property/finance/projects/{projectId}/standards")
@Resource(id = "property-finance-standard", name = "收费标准")
@Authorize
@RequiredArgsConstructor
public class StandardController {
    private final StandardService service;
    private final FormulaVariableService variables;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<StandardView>> query(
            @PathVariable String projectId,
            @RequestBody QueryParamEntity query,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String effectiveStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate businessDate) {
        return service.query(
                projectId, query, categoryId, subjectId, effectiveStatus, businessDate);
    }

    @PostMapping("/_export")
    @ResourceAction(id = "export", name = "导出")
    public Mono<ResponseEntity<byte[]>> export(
            @PathVariable String projectId,
            @RequestBody QueryParamEntity query,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String effectiveStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate businessDate) {
        return service.export(
                        projectId, query, categoryId, subjectId, effectiveStatus, businessDate)
                .map(
                        bytes ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(
                                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=standards.xlsx")
                                        .body(bytes));
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<StandardView> detail(@PathVariable String projectId, @PathVariable String id) {
        return service.detail(projectId, id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<StandardView> create(
            @PathVariable String projectId, @RequestBody StandardInput input) {
        return service.create(projectId, input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<StandardView> update(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody StandardInput input) {
        return service.update(projectId, id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @PathVariable String projectId, @PathVariable String id, @RequestParam Long version) {
        return service.delete(projectId, id, version);
    }

    @GetMapping("/_options")
    @ResourceAction(id = "query", name = "查询")
    public Mono<Map<String, Object>> options(@PathVariable String projectId) {
        return service.options(projectId);
    }

    @PostMapping("/tax-options/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<TaxItemEntity>> taxOptions(
            @PathVariable String projectId, @RequestBody QueryParamEntity query) {
        return service.taxOptions(projectId, query);
    }

    @PostMapping("/formula/_validate")
    @ResourceAction(id = "query", name = "查询")
    public Mono<StandardRules.FormulaResult> formula(
            @PathVariable String projectId, @RequestBody FormulaInput input) {
        return service.validateFormula(
                projectId, input.formulaExpression(), input.formulaDisplay());
    }

    @GetMapping("/variables")
    @ResourceAction(id = "query", name = "查询")
    public Flux<VariableDefinition> variables(@PathVariable String projectId) {
        return variables.list(projectId);
    }

    @PostMapping("/variables")
    @ResourceAction(id = "save", name = "保存")
    public Mono<VariableDefinition> createVariable(
            @PathVariable String projectId, @RequestBody VariableInput input) {
        return variables.create(projectId, input);
    }

    @PutMapping("/variables/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<VariableDefinition> updateVariable(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody VariableInput input) {
        return variables.update(projectId, id, input);
    }

    @DeleteMapping("/variables/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> deleteVariable(
            @PathVariable String projectId, @PathVariable String id, @RequestParam Long version) {
        return variables.delete(projectId, id, version);
    }

    public record FormulaInput(String formulaExpression, String formulaDisplay) {}
}

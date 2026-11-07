package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryOption;
import org.jetlinks.pro.property.finance.entity.SubjectEntity;
import org.jetlinks.pro.property.finance.service.*;
import org.jetlinks.pro.property.finance.subject.SubjectInput;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/property/finance/subjects")
@Resource(id = "property-finance-subject", name = "收费科目")
@Authorize
@RequiredArgsConstructor
public class SubjectController {
    private final SubjectService service;
    private final ProjectSubjectService projects;
    private final SubjectDictionaryService dictionaries;
    private final SubjectExcelService excel;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<SubjectEntity>> query(
            @RequestBody QueryParamEntity input,
            @RequestParam(required = false) String categoryId) {
        return service.query(input, categoryId);
    }

    @GetMapping("/_fee-types")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertyDictionaryOption>> types() {
        return dictionaries.options("charge-item-in-type");
    }

    @GetMapping("/{id}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<SubjectEntity> detail(@PathVariable String id) {
        return service.detail(id);
    }

    @PostMapping
    @ResourceAction(id = "save", name = "保存")
    public Mono<SubjectEntity> create(@RequestBody SubjectInput input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<SubjectEntity> update(@PathVariable String id, @RequestBody SubjectInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(@PathVariable String id, @RequestParam long version) {
        return service.delete(id, version);
    }

    @PostMapping("/_issue")
    @ResourceAction(id = "issue", name = "下发项目")
    public Mono<ProjectSubjectService.IssueResult> issue(
            @RequestBody ProjectSubjectService.IssueInput input) {
        return projects.issue(input);
    }

    @GetMapping("/template.xlsx")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> template(ServerHttpResponse response) {
        response.getHeaders()
                .set(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.getHeaders()
                .set("Content-Disposition", "attachment; filename=charge-subject-template.xlsx");
        return excel.template()
                .flatMap(
                        bytes ->
                                response.writeWith(
                                        Mono.just(response.bufferFactory().wrap(bytes))));
    }

    @PostMapping("/_import")
    @ResourceAction(id = "import", name = "导入")
    public Mono<SubjectExcelService.ImportResult> importFile(@RequestPart("file") FilePart file) {
        return excel.importFile(file);
    }

    @PostMapping("/_delete")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> deleteBatch(@RequestBody List<SubjectService.VersionedId> input) {
        return service.deleteBatch(input);
    }

    @PostMapping("/imports/_query")
    @ResourceAction(id = "imports", name = "导入记录")
    public Mono<PagerResult<org.jetlinks.pro.property.finance.entity.SubjectImportEntity>> imports(
            @RequestBody QueryParamEntity input) {
        return excel.records(input);
    }
}

package org.jetlinks.pro.property.base.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.service.ArchiveExcelService;
import org.jetlinks.pro.property.base.service.PropertyHouseService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

/** 房屋资料专用接口；逻辑删除扩展记录，不能修改或删除 SaaS 空间节点。 */
@RestController
@RequestMapping("/property/house")
@Resource(id = "property-house", name = "物业空间档案")
@Authorize
@RequiredArgsConstructor
public class PropertyHouseController {
    private final PropertyHouseService houses;
    private final ArchiveExcelService excel;

    @PostMapping("/_query")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PagerResult<PropertyHouseService.HouseView>> query(
            @RequestParam String projectId,
            @RequestParam(required = false) String spaceId,
            @RequestBody QueryParamEntity query) {
        return houses.query(projectId, spaceId, query);
    }

    @GetMapping("/{spaceId}")
    @ResourceAction(id = "query", name = "查询")
    public Mono<PropertyHouseService.HouseView> detail(
            @RequestParam String projectId, @PathVariable String spaceId) {
        return houses.detail(projectId, spaceId);
    }

    @PutMapping("/{spaceId}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<PropertyHouseService.HouseView> save(
            @RequestParam String projectId,
            @PathVariable String spaceId,
            @RequestBody PropertyHouseService.HouseWrite input) {
        return houses.save(projectId, spaceId, input);
    }

    @DeleteMapping("/{spaceId}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> delete(
            @RequestParam String projectId,
            @PathVariable String spaceId,
            @RequestParam long version) {
        return houses.delete(projectId, spaceId, version);
    }

    @GetMapping("/template.xlsx")
    @ResourceAction(id = "import", name = "导入")
    public Mono<Void> template(@RequestParam String projectId, ServerHttpResponse response) {
        return excel.template(projectId, "house")
                .flatMap(document -> excel.write(document, response));
    }

    @PostMapping(value = "/_import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourceAction(id = "import", name = "导入")
    public Mono<PropertyHouseService.ImportResult> importFile(
            @RequestParam String projectId, @RequestPart("file") FilePart file) {
        return excel.importHouses(projectId, file);
    }

    @PostMapping("/_export")
    @ResourceAction(id = "export", name = "导出")
    public Mono<Void> export(
            @RequestParam String projectId,
            @RequestParam(required = false) String spaceId,
            @RequestBody QueryParamEntity query,
            ServerHttpResponse response) {
        return excel.exportHouses(projectId, spaceId, query)
                .flatMap(document -> excel.write(document, response));
    }
}

package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.pro.patrol.service.PatrolPointTransferService;
import org.jetlinks.pro.patrol.service.PatrolPointWorkbook;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 文件操作复用点位 query/save 功能权限；资产读取与新资产绑定由 transfer service 统一拥有。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/points/workbook")
@Resource(id = "patrol-point", name = "巡更点位")
public class PatrolPointTransferController {
    private final PatrolPointTransferService service;

    @GetMapping("/template")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> template() { return service.template().map(bytes -> file(bytes, "patrol-point-template.xlsx")); }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaveAction
    public Mono<PatrolPointWorkbook.Preview> preview(@RequestPart("file") FilePart file) { return bytes(file).flatMap(service::preview); }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaveAction
    public Mono<Integer> importPoints(@RequestPart("file") FilePart file) { return bytes(file).flatMap(service::importPoints); }

    @PostMapping("/export")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> export(@RequestBody QueryParamEntity query) {
        return service.export(query).map(bytes -> file(bytes, "patrol-points.xlsx"));
    }

    private Mono<byte[]> bytes(FilePart file) {
        return DataBufferUtils.join(file.content(), 10 * 1024 * 1024).map(buffer -> {
            try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
            finally { DataBufferUtils.release(buffer); }
        });
    }
    private ResponseEntity<byte[]> file(byte[] bytes, String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name).body(bytes);
    }
}

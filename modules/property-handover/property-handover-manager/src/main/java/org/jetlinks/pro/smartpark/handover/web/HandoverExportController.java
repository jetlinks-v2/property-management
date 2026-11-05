package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.pro.smartpark.handover.api.ExportRequest;
import org.jetlinks.pro.smartpark.handover.entity.HandoverExportEntity;
import org.jetlinks.pro.smartpark.handover.export.HandoverExportService;
import org.jetlinks.pro.smartpark.handover.service.HandoverQuery;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/export")
@Resource(id = "handover-export", name = "查验导出")
public class HandoverExportController {
    private final HandoverExportService service;

    @PostMapping
    @ResourceAction(id = "export", name = "导出")
    public Mono<HandoverExportEntity> create(@RequestBody ExportRequest input) {
        return service.create(input);
    }

    @GetMapping("/_query")
    @QueryAction
    public Mono<PagerResult<HandoverExportEntity>> query(HandoverQuery input) {
        return service.query(input);
    }

    @PostMapping("/{id}/retry")
    @ResourceAction(id = "export", name = "重试导出")
    public Mono<HandoverExportEntity> retry(@PathVariable String id) {
        return service.retry(id);
    }

    @GetMapping("/{id}/download")
    @ResourceAction(id = "export", name = "下载导出")
    public Mono<ResponseEntity<Flux<DataBuffer>>> download(@PathVariable String id) {
        return service.authorizeDownload(id)
                .map(
                        task ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(
                                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.attachment()
                                                        .filename(
                                                                task.getFileName(),
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(service.download(task)));
    }
}

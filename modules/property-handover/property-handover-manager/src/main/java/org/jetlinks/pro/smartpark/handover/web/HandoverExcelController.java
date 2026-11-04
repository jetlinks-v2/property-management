package org.jetlinks.pro.smartpark.handover.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.pro.smartpark.handover.excel.HandoverExcelService;
import org.jetlinks.pro.smartpark.handover.excel.HandoverImportException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 自定义Excel入口保留ResourceAction和项目资产校验，不开放框架通用写入绕过预检。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/property/handover/excel")
@Authorize
public class HandoverExcelController {
    private final HandoverExcelService service;

    @GetMapping("/{kind}/template")
    public Mono<Void> template(
            @PathVariable String kind,
            @RequestParam String projectId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String stage,
            ServerHttpResponse response) {
        response.getHeaders()
                .setContentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        response.getHeaders()
                .setContentDisposition(
                        ContentDisposition.attachment()
                                .filename("承接查验-" + kind + "-导入模板.xlsx", StandardCharsets.UTF_8)
                                .build());
        return response.writeWith(service.template(kind, projectId, source, stage));
    }

    @PostMapping(path = "/{kind}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Long>> importFile(
            @PathVariable String kind,
            @RequestParam String projectId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String stage,
            @RequestPart("file") FilePart file) {
        return service.importFile(kind, projectId, source, stage, file);
    }

    @ExceptionHandler(HandoverImportException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidExcel(HandoverImportException error) {
        return Map.of(
                "status",
                400,
                "code",
                "error.handover.excel_validation_failed",
                "message",
                "导入校验未通过，请按行号修正后重试",
                "result",
                Map.of("errors", error.getErrors()));
    }
}

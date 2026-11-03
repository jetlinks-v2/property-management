package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.pro.patrol.service.PatrolRosterImportService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

/** 点位管理的月模板下载/整月导入，沿用 patrol-roster 功能权限。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/rosters/month")
@Resource(id = "patrol-roster", name = "巡更排班")
public class PatrolRosterImportController {
    private final PatrolRosterImportService service;

    @GetMapping("/template")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> template(@RequestParam String month) {
        return service.template(YearMonth.parse(month)).map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(month + "-roster.xlsx", StandardCharsets.UTF_8).build().toString()).body(bytes));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaveAction
    public Mono<Integer> importMonth(@RequestParam String month, @RequestPart("file") FilePart file) {
        // 限制聚合后的文件大小；POI 解压/解析在服务内 boundedElastic 上执行。
        return DataBufferUtils.join(file.content(), 5 * 1024 * 1024)
                .map(buffer -> {
                    try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
                    finally { DataBufferUtils.release(buffer); }
                }).flatMap(bytes -> service.importMonth(YearMonth.parse(month), bytes));
    }
}

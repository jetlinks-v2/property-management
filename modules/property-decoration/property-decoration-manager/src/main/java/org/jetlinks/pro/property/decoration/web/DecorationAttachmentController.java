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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBuffer;
/** 上传和预览仅暴露业务回执，不返回私有文件的通用访问URL。 */
@RestController @Authorize @RequiredArgsConstructor
@RequestMapping("/decoration/attachments") @Resource(id="decoration-attachment",name="装修附件")
public class DecorationAttachmentController {
    private final DecorationAttachmentService attachments;
    @PostMapping @SaveAction
    public Mono<DecorationAttachmentService.Receipt> upload(@RequestParam String projectId,
        @RequestParam(required=false) String decorationId, @RequestParam(defaultValue="draft") String purpose,
        @RequestPart("file") Mono<FilePart> file) {
        return file.flatMap(part -> attachments.upload(projectId, decorationId, purpose, part));
    }
    @GetMapping("/{id}/metadata") @QueryAction
    public Mono<DecorationAttachmentService.Receipt> metadata(@PathVariable String id) { return attachments.metadata(id); }
    @GetMapping("/{id}/word-text") @QueryAction
    public Mono<String> wordText(@PathVariable String id) { return attachments.wordText(id); }
    @GetMapping("/{id}") @QueryAction
    public Mono<ResponseEntity<Flux<DataBuffer>>> download(@PathVariable String id) { return attachments.download(id); }
}

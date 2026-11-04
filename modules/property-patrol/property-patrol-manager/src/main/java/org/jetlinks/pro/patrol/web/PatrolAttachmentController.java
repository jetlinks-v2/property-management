package org.jetlinks.pro.patrol.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose;
import org.jetlinks.pro.patrol.service.PatrolAttachmentService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 文件登记及预览走巡更授权；不开放通用附件 CRUD 或任意远端 URL 下载。 */
@RestController
@AllArgsConstructor
@RequestMapping("/park/patrol/attachments")
@Resource(id = "patrol-attachment", name = "巡更图片")
public class PatrolAttachmentController {
    private final PatrolAttachmentService service;

    @PostMapping(value = "/{purpose}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaveAction
    public Mono<PatrolAttachmentService.Receipt> upload(@PathVariable Purpose purpose, @RequestPart("file") FilePart file) {
        return service.upload(file, purpose);
    }

    @GetMapping("/{id}/preview")
    @QueryAction
    public Mono<PatrolAttachmentService.Preview> preview(@PathVariable String id) {
        return service.preview(id);
    }
}

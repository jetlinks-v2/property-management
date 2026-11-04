package org.jetlinks.pro.smartpark.repair.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.logging.AccessLogger;
import org.jetlinks.pro.smartpark.repair.api.PublicRepairRequest;
import org.jetlinks.pro.smartpark.repair.service.RepairPublicService;
import org.jetlinks.pro.smartpark.repair.service.RepairQrUploadService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

/** 公开范围由二维码能力provider拥有；不暴露管理端CRUD，也不忽略其AssetsHolder保护。 */
@RestController @AllArgsConstructor @Authorize(ignore=true)
@AccessLogger(value="二维码匿名报事",ignoreParameter={"input","file"})
@RequestMapping("/repair/public/qr/{code}")
public class RepairPublicController {
    private final RepairPublicService service;
    private final RepairQrUploadService uploads;
    @GetMapping @AccessLogger("查看报事表单")
    public Mono<Map<String,Object>> form(@PathVariable String code){return service.form(code);}
    @PostMapping("/orders") @AccessLogger("提交匿名报事")
    public Mono<Map<String,String>> submit(@PathVariable String code,@jakarta.validation.Valid @RequestBody PublicRepairRequest input){return service.submit(code,input);}
    @PostMapping("/uploads") @AccessLogger("上传匿名报事附件")
    public Mono<Map<String,String>> upload(@PathVariable String code,@RequestPart("file") Mono<FilePart> file){
        return file.flatMap(part->uploads.upload(code,part)).map(id->Map.of("uploadId",id));
    }
}

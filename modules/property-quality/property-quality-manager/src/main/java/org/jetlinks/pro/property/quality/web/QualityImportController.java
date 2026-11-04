package org.jetlinks.pro.property.quality.web;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.core.io.buffer.*;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.hswebframework.web.authorization.annotation.*;
import org.hswebframework.reactor.excel.*;
import org.jetlinks.pro.io.excel.ExcelUtils;
import org.jetlinks.pro.property.quality.service.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import java.util.stream.IntStream;
/** 有界上传、I/O线程解析，事务写入前执行整表校验。 */
@RestController @RequiredArgsConstructor @RequestMapping("/quality/import") @Resource(id="quality-configuration",name="品质配置")
public class QualityImportController {
 private final QualityImportManager manager;
 @PostMapping(value="/{target}",consumes="multipart/form-data") @SaveAction
 public Mono<Map<String,Integer>> upload(@PathVariable String target,@RequestParam int level,@RequestParam(required=false)String projectId,@RequestPart("file") FilePart file,@RequestParam(defaultValue="false")boolean company){
  return DataBufferUtils.join(file.content(),10*1024*1024).map(buffer->{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);return bytes;})
   .flatMap(bytes->Mono.fromCallable(()->QualityWorkbook.parse(bytes,target,level)).subscribeOn(Schedulers.boundedElastic())).flatMap(rows->manager.apply(rows,target,level,projectId,company));
 }
 @GetMapping("/{target}/template") @QueryAction public Mono<Void> template(@PathVariable String target,@RequestParam(defaultValue="1")int level,ServerHttpResponse response){
  QualityScope.require(Set.of("subjects","basic","task").contains(target),"import_target");var names=QualityWorkbook.headers(target,level);var headers=IntStream.range(0,names.size()).mapToObj(i->new ExcelHeader("c"+i,names.get(i),CellDataType.STRING)).toList();
  response.getHeaders().set("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.getHeaders().set("Content-Disposition","attachment; filename=quality-template.xlsx");return response.writeWith(ExcelUtils.write(headers,Flux.empty(),"xlsx").subscribeOn(Schedulers.boundedElastic()));
 }
}

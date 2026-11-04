package org.jetlinks.pro.property.web;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.service.PropertySyncService;
import reactor.core.publisher.Mono;
import java.util.Map;
/** 同步入口要求明确功能授权，并由远程 Cloud 命令限制当前用户可访问的源目录。 */
@RestController @AllArgsConstructor
@RequestMapping("/property-data")
@Resource(id="property-data",name="物业共享基础数据")
public class PropertySyncController {
 private final PropertySyncService sync;
 @PostMapping("/_sync") @ResourceAction(id="sync",name="同步Cloud基础数据")
 public Mono<Map<String,Long>> sync() {return sync.sync();}
}

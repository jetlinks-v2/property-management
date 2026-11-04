package org.jetlinks.pro.smartpark.repair.web;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.pro.smartpark.repair.entity.RepairOperationLogEntity;
import org.jetlinks.pro.smartpark.repair.service.RepairContextProvider;
import org.jetlinks.pro.smartpark.repair.service.RepairOperationLogService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 只读应用审计入口；专用权限只向应用管理员授权，不提供日志写入或删除接口。 */
@RestController @AllArgsConstructor @RequestMapping("/repair/operation-logs")
@Resource(id="repair-operation-log",name="报事操作日志")
public class RepairOperationLogController {
    private final RepairContextProvider contexts;
    private final RepairOperationLogService logs;
    @PostMapping("/_query") @QueryAction
    public Mono<PagerResult<RepairOperationLogEntity>> query(@RequestBody QueryParamEntity query) {
        return contexts.current().flatMap(context->logs.query(context,query));
    }
}

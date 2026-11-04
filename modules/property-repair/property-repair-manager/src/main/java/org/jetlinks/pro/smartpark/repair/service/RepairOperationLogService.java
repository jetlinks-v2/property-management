package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.repair.entity.RepairOperationLogEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 日志是应用管理员的整应用资源，不套用工单的组织级过滤。 */
@Service
public class RepairOperationLogService extends GenericReactiveCrudService<RepairOperationLogEntity,String> {
    public Mono<PagerResult<RepairOperationLogEntity>> query(RepairContext context,QueryParamEntity input) {
        return queryPager(scoped(context,input));
    }

    static QueryParamEntity scoped(RepairContext context,QueryParamEntity input) {
        QueryParamEntity query=input.clone().toNestQuery()
            .and("tenantId",context.tenantId()).and("applicationId",context.applicationId()).getParam();
        query.setPaging(true);
        query.setPageSize(Math.min(Math.max(query.getPageSize(),1),100));
        if(query.getSorts().isEmpty())query.toQuery().orderByDesc("createTime").orderByDesc("id");
        return query;
    }
}

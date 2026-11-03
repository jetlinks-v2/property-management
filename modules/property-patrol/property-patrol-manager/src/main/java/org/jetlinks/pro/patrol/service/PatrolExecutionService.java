package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.springframework.stereotype.Service;

/** 巡更Execution持久化；不暴露通用写入接口。 */
@Service
public class PatrolExecutionService
        extends GenericReactiveCrudService<PatrolExecutionEntity, String> {
    public PatrolExecutionService(ReactiveRepository<PatrolExecutionEntity, String> repository) {
        super(repository);
    }
}

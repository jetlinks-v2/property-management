package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.patrol.entity.PatrolWarningEntity;
import org.springframework.stereotype.Service;

/** 异常预警配置持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolWarningService
        extends PatrolRemovableConfigService<PatrolWarningEntity> {
    public PatrolWarningService(ReactiveRepository<PatrolWarningEntity, String> repository) {
        super(repository);
    }
}

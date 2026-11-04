package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.springframework.stereotype.Service;

/** 巡更标准持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolStandardService
        extends PatrolRemovableConfigService<PatrolStandardEntity> {
    public PatrolStandardService(ReactiveRepository<PatrolStandardEntity, String> repository) {
        super(repository);
    }
}

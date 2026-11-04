package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 配置文件读取边界：当前应用与资产授权同时生效，导出完整筛选结果，不能静默截断。 */
@Service
@AllArgsConstructor
public class PatrolConfigTransferService {
    public static final int LIMIT = 10000;
    private final PatrolScope scope;

    public <E extends PatrolConfigEntity> Mono<byte[]> export(GenericReactiveCrudService<E, String> service,
            PatrolAssetType type, QueryParamEntity query) {
        query.setPageIndex(0); query.setPageSize(LIMIT + 1); query.setPaging(true);
        return scope.current().flatMap(current -> {
            query.toNestQuery().and("scopeKey", current.key());
            return AssetsHolder.injectQueryParam(query, type, "id").flatMap(service::queryPager)
                    .flatMap(page -> page.getTotal() > LIMIT
                            ? Mono.error(new BusinessException("error.patrol.config.export_limit"))
                            : Mono.fromCallable(() -> PatrolConfigWorkbook.write(type, page.getData()))
                                    .subscribeOn(Schedulers.boundedElastic()));
        });
    }
}

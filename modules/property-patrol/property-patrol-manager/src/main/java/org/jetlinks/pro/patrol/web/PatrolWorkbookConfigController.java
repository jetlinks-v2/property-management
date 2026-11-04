package org.jetlinks.pro.patrol.web;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.assets.annotation.AssetsController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.service.PatrolConfigTransferService;
import org.jetlinks.pro.patrol.service.PatrolConfigurationValidator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Mono;

/** 等级、标准、标签复用配置 CRUD；文件导出复用 query 权限，不新增角色授权项。 */
public abstract class PatrolWorkbookConfigController<E extends PatrolConfigEntity> extends PatrolConfigController<E> {
    private final GenericReactiveCrudService<E, String> service;
    private final PatrolAssetType type;
    private final PatrolConfigTransferService transfer;

    protected PatrolWorkbookConfigController(GenericReactiveCrudService<E, String> service,
            PatrolConfigurationValidator validator, PatrolAssetType type, PatrolConfigTransferService transfer) {
        super(service, validator, type);
        this.service = service; this.type = type; this.transfer = transfer;
    }

    /** 等价资产查询注入由 transfer 拥有，避免自动拦截器把文件端点当实体详情接口。 */
    @PostMapping("/_export")
    @QueryAction
    @AssetsController(ignore = true)
    public Mono<ResponseEntity<byte[]>> export(@RequestBody QueryParamEntity query) {
        return transfer.export(service, type, query).map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=patrol-config.xlsx").body(bytes));
    }
}

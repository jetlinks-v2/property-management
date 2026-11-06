package org.jetlinks.pro.property.quality.service;
import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.quality.entity.QualityRecordEntity;
/** 品质Record响应式仓储；权限和状态编排由领域入口负责。 */
@Service public class QualityRecordService extends GenericReactiveCrudService<QualityRecordEntity,String> {}

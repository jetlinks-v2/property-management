package org.jetlinks.pro.smartpark.repair.service;
import org.springframework.stereotype.Service;import org.hswebframework.web.crud.service.GenericReactiveCrudService;import org.jetlinks.pro.smartpark.repair.entity.QualityRepairLinkEntity;
/** 品质来源映射持久化，不向 HTTP 暴露通用 CRUD。 */
@Service public class QualityRepairLinkService extends GenericReactiveCrudService<QualityRepairLinkEntity,String> {}

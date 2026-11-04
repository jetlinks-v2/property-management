package org.jetlinks.pro.smartpark.repair.service;
import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.repair.entity.RepairExportEntity;
/** 导出记录持久化；可见范围由导出应用服务统一拥有。 */
@Service
public class RepairExportRepository extends GenericReactiveCrudService<RepairExportEntity,String> {}

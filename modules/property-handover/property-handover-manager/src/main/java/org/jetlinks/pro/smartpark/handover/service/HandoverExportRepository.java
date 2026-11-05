package org.jetlinks.pro.smartpark.handover.service;
import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.handover.entity.HandoverExportEntity;
/** Persistence only; authorization and transactions belong to the application service. */
@Service
public class HandoverExportRepository extends GenericReactiveCrudService<HandoverExportEntity,String> {}

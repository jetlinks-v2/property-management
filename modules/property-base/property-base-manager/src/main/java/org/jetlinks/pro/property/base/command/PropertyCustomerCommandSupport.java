package org.jetlinks.pro.property.base.command;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Component;import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;import org.jetlinks.core.annotation.command.CommandHandler;import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.base.customer.PropertyCustomerInfo;import org.jetlinks.pro.property.base.service.PropertyCustomerService;import reactor.core.publisher.Flux;
/** 客户跨服务只读owner，复用基础档案当前关系和资产范围，不允许消费方读取pm表。 */
@Component @Authorize @RequiredArgsConstructor
@CommandService(id=QueryPropertyCustomersCommand.SERVICE_ID,name="物业客户目录",description="当前有效客户及有权房屋的最小投影")
public class PropertyCustomerCommandSupport {
 private final PropertyCustomerService customers;
 @CommandHandler(name="查询物业客户") public Flux<PropertyCustomerInfo> query(QueryPropertyCustomersCommand command){
  return customers.export(command.getProjectId(),command.getSpaceId(),QueryParamEntity.newQuery().noPaging().getParam())
   .flatMapMany(Flux::fromIterable).filter(v->v.getRelationships()!=null&&!v.getRelationships().isEmpty()).map(v->{
    var info=new PropertyCustomerInfo();info.setId(v.getId());info.setProjectId(command.getProjectId());info.setUserId(v.getUserId());info.setName(v.getName());info.setTelephone(v.getTelephone());
    info.setSpaceIds(v.getRelationships().stream().map(link->link.getSpaceId()).distinct().toList());return info;
   });
 }
}

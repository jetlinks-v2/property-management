package org.jetlinks.pro.property.base.command;
import org.jetlinks.core.command.AbstractCommand;import org.jetlinks.pro.property.base.customer.PropertyCustomerInfo;import reactor.core.publisher.Flux;
/** 业务后台读取指定项目当前有效客户，保留认证、租户和空间AssetsHolder权限；只读，不触发同步。 */
public class QueryPropertyCustomersCommand extends AbstractCommand<Flux<PropertyCustomerInfo>,QueryPropertyCustomersCommand> {
 public static final String SERVICE_ID="propertyService:customer";
 public String getProjectId(){return getOrNull("projectId",String.class);}
 public void setProjectId(String v){with("projectId",v);}
 public String getSpaceId(){return getOrNull("spaceId",String.class);}
 public void setSpaceId(String v){with("spaceId",v);}
}

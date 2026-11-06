package org.jetlinks.pro.property.quality.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.BusinessApplicationDimension;
import org.jetlinks.pro.property.quality.entity.QualityEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import reactor.core.publisher.Mono;
import java.util.Objects;
import java.util.function.Supplier;
/** 持久化后台作业恢复原操作人的当前授权；撤权后不会继续使用旧权限快照。 */
@Component @RequiredArgsConstructor
public class QualityExecution {
 private final AssetScopeResolverManager resolver;
 public <T> Mono<T> asOwner(QualityEntity row,Supplier<Mono<T>> work){
  return asUser(row,row.getCreatorId(),work);
 }
 /** 通知等后台工作以收件人的实时身份访问公开命令，不能依赖调度线程上不存在的请求身份。 */
 public <T> Mono<T> asUser(QualityEntity row,String userId,Supplier<Mono<T>> work){
  return ReactiveAuthenticationHolder.get(userId)
   .filter(auth->auth.getDimensions(BusinessApplicationDimension.TYPE).stream().anyMatch(d->row.getApplicationId().equals(d.getId())))
   .flatMap(auth->resolver.resolve(AssetScope.of(BusinessApplicationDimension.TYPE,row.getApplicationId()),auth))
   .switchIfEmpty(Mono.error(new AccessDenyException()))
   .flatMap(active->AssetsHolder.fromAuth(active.authentication(),active.context()).flatMap(holder->Mono.defer(work)
    .contextWrite(c->c.put(Authentication.class,active.authentication()).put(AssetsHolder.class,holder))
    .contextWrite(AssetScopeContext.replace(active.context()))))
   .contextWrite(TenantHolder.makeCurrent(Objects.toString(row.getTenantId())));
 }
}

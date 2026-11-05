package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.FastSerializableAuthentication;
import org.jetlinks.pro.codec.Serializers;
import org.jetlinks.pro.tenant.holder.TenantHolder;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/** 后台只恢复数据库中已保存的授权，不接受任务调用方传入用户或scope；HTTP仍走原权限入口。 */
final class FinanceJobAuthority {
    private FinanceJobAuthority() {}

    static Mono<String> capture(Authentication auth, String errorCode) {
        return Mono.deferContextual(
                view -> {
                    Map<String, Object> scopes = new HashMap<>();
                    new AssetScopeContextSerializeSupport().serialize(scopes, view).dispose();
                    String encoded =
                            JSON.toJSONString(
                                    Map.of(
                                            "authentication",
                                            serialize(auth, errorCode),
                                            "scope",
                                            scopes));
                    if (encoded.length() > 60000) return Mono.error(error(errorCode, 409));
                    return Mono.just(encoded);
                });
    }

    /** 当前身份与保存时的个人token/维度上限取交集，再恢复保存时的应用范围，不能仅getByUserId。 */
    static Mono<Context> restore(
            String snapshot,
            String tenantId,
            Authentication current,
            Predicate<Authentication> allowed,
            String errorCode) {
        return Mono.defer(
                        () -> {
                            var saved = saved(snapshot, errorCode);
                            if (current == null
                                    || current.getUser() == null
                                    || !Objects.equals(
                                            saved.getUser().getId(), current.getUser().getId())
                                    || !allowed.test(saved)
                                    || !allowed.test(current))
                                return Mono.error(error(errorCode, 403));
                            // 官方认证编解码生成独立容器，保留有类型的ceiling；不能修改认证缓存的共享实例。
                            var effective = deserialize(serialize(current, errorCode), errorCode);
                            var encoded = JSON.parseObject(snapshot);
                            var context =
                                    new AssetScopeContextSerializeSupport()
                                            .deserialize(
                                                    Context.empty(),
                                                    encoded.getJSONObject("scope"));
                            // scope-only维度是应用的限制条件，不是普通资产授权；用正式activation恢复其策略。
                            for (var scope : AssetScopeContext.current(context).getScopes()) {
                                var dimension =
                                        saved.getDimension(scope.type(), scope.id())
                                                .orElseThrow(() -> error(errorCode, 403));
                                var grant =
                                        AssetScopeGrant.decode(
                                                dimension
                                                        .getOptions()
                                                        .get(AssetScopeGrant.OPTION_KEY));
                                effective =
                                        AssetScopeActivation.activate(
                                                        effective,
                                                        dimension,
                                                        grant.getAssetStrategies())
                                                .authentication();
                            }
                            AssetPermissionCeiling.inherit(saved, effective);
                            if (tenantId != null && !tenantId.isBlank())
                                context =
                                        context.putAll(
                                                TenantHolder.makeCurrent(tenantId).readOnly());
                            Context base = context.put(Authentication.class, effective);
                            return AssetsHolder.fromAuth(effective, AssetScopeContext.current(base))
                                    .switchIfEmpty(Mono.error(error(errorCode, 403)))
                                    .map(holder -> base.put(AssetsHolder.class, holder));
                        })
                .onErrorMap(
                        failure ->
                                failure
                                                instanceof
                                                org.hswebframework.web.exception.BusinessException
                                        ? failure
                                        : error(errorCode, 403));
    }

    static String executorId(String snapshot, String errorCode) {
        return saved(snapshot, errorCode).getUser().getId();
    }

    private static Authentication saved(String snapshot, String errorCode) {
        try {
            var encoded = JSON.parseObject(snapshot);
            if (encoded == null
                    || encoded.getString("authentication") == null
                    || encoded.getJSONObject("scope") == null) throw error(errorCode, 403);
            var saved = deserialize(encoded.getString("authentication"), errorCode);
            if (saved.getUser() == null || saved.getUser().getId() == null)
                throw error(errorCode, 403);
            return saved;
        } catch (RuntimeException failure) {
            throw error(errorCode, 403);
        }
    }

    private static String serialize(Authentication auth, String errorCode) {
        // 使用平台公开的JDK策略，持久格式不依赖进程默认FST或其JVM模块开放参数。
        var buffer = new ByteArrayOutputStream();
        try (var output = Serializers.jdk().createOutput(buffer)) {
            output.writeObject(FastSerializableAuthentication.of(auth));
            output.flush();
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException failure) {
            throw error(errorCode, 409);
        }
    }

    private static Authentication deserialize(String encoded, String errorCode) {
        try (var input =
                Serializers.jdk()
                        .createInput(
                                new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return (Authentication) input.readObject();
        } catch (IOException | ClassNotFoundException failure) {
            throw error(errorCode, 403);
        }
    }
}

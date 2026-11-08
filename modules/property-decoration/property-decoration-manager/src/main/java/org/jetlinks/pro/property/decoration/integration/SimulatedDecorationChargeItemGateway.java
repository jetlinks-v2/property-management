package org.jetlinks.pro.property.decoration.integration;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.decoration.model.DecorationChargeItemGateway;
import org.jetlinks.pro.property.decoration.service.DecorationRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/** 仅显式模拟模式提供测试科目，不访问收费主档；存在正式提供方时优先使用正式提供方。 */
@Component
@Fallback
@ConditionalOnProperty(name = "decoration.integrations.simulated.enabled", havingValue = "true")
public class SimulatedDecorationChargeItemGateway implements DecorationChargeItemGateway {
    private static final List<ChargeItem> ITEMS = List.of(
        new ChargeItem("SIM-DECORATION-DEPOSIT", "装修保证金（模拟）"),
        new ChargeItem("SIM-DECORATION-SERVICE", "装修服务费（模拟）")
    );

    /** 项目、员工及应用权限仍由原目录入口检查，不因模拟模式放宽。 */
    @Override
    public Mono<PagerResult<ChargeItem>> query(String projectId, QueryParamEntity request) {
        return Mono.fromSupplier(() -> page(request));
    }

    private PagerResult<ChargeItem> page(QueryParamEntity request) {
        var query = request.clone();
        query.setPaging(true);
        query.setPageIndex(Math.max(0, query.getPageIndex()));
        query.setPageSize(Math.max(1, Math.min(100, query.getPageSize())));
        String keyword = keyword(query).toLowerCase(Locale.ROOT);
        var matches = ITEMS.stream().filter(item -> item.name().toLowerCase(Locale.ROOT).contains(keyword)).toList();
        int start = (int) Math.min(matches.size(), (long) query.getPageIndex() * query.getPageSize());
        int end = Math.min(matches.size(), start + query.getPageSize());
        return PagerResult.of(matches.size(), matches.subList(start, end), query);
    }

    private String keyword(QueryParamEntity query) {
        if (query.getTerms().isEmpty()) return "";
        var term = query.getTerms().get(0);
        // 测试目录只支持当前选择器的名称包含搜索，不静默忽略其他查询条件。
        boolean supported = query.getTerms().size() == 1 && term.getTerms().isEmpty()
            && "name".equals(term.getColumn()) && "like".equals(term.getTermType())
            && term.getValue() instanceof String;
        if (!supported) throw DecorationRules.error("invalid_simulated_charge_query", 400);
        return (String) term.getValue();
    }
}

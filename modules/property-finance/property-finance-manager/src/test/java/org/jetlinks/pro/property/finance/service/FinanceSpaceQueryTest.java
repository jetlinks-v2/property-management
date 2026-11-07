package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;

class FinanceSpaceQueryTest {
    @Test
    void explicitPlatformUnrestrictedScopeKeepsQueryIncludingUnassignedSpaces() {
        var holder = mock(AssetsHolder.class, CALLS_REAL_METHODS);
        when(holder.getPermissionState(any(AssetType.class)))
                .thenReturn(Mono.just(AssetPermissionState.UNRESTRICTED));
        ReactiveRepository<Object, String> rows = mock(ReactiveRepository.class);
        var query = new QueryParamEntity();
        query.toQuery().isNull("spaceId");
        assertSame(
                query,
                FinanceSpaceQuery.inject(query, rows, CrudAssetPermission.read)
                        .contextWrite(Context.of(AssetsHolder.class, holder))
                        .block(Duration.ofSeconds(5)));
        verifyNoInteractions(rows);
    }

    @Test
    void missingAssetContextDeniesBeforeReadingCandidateRows() {
        ReactiveRepository<Object, String> rows = mock(ReactiveRepository.class);
        StepVerifier.create(
                        FinanceSpaceQuery.inject(
                                new QueryParamEntity(), rows, CrudAssetPermission.read))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify(Duration.ofSeconds(5));
        verifyNoInteractions(rows);
    }
}

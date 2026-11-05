package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.base.command.PropertyEmployeeCommandSupport;
import org.jetlinks.pro.property.base.command.QueryPropertyEmployeePageCommand;
import org.jetlinks.pro.property.base.entity.PropertyUserEntity;
import org.jetlinks.pro.property.base.entity.PropertyUserOrganizationEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PropertyEmployeeScopeTest {
    private boolean hasEmployee(java.util.List<org.hswebframework.ezorm.core.param.Term> terms) {
        return terms.stream().anyMatch(term -> "employee".equals(term.getColumn()) || hasEmployee(term.getTerms()));
    }
    @Test
    @SuppressWarnings("unchecked")
    void directoryDenialPropagatesBeforeAnyEmployeeOrOrganizationRead() {
        ReactiveRepository<PropertyUserEntity, String> users = mock(ReactiveRepository.class);
        ReactiveRepository<PropertyUserOrganizationEntity, String> orgs = mock(ReactiveRepository.class);
        var scope = mock(PropertyUserScope.class);
        when(scope.query(any())).thenAnswer(call -> {
            QueryParamEntity query = call.getArgument(0);
            assertTrue(query.isPaging());
            assertEquals(200, query.getPageSize());
            assertTrue(hasEmployee(query.getTerms()));
            return Mono.error(new AccessDenyException.NoStackTrace());
        });
        var command = new QueryPropertyEmployeePageCommand();
        var input = new QueryParamEntity();
        input.setPageSize(1000);
        command.setQuery(input);
        StepVerifier.create(new PropertyEmployeeCommandSupport(users, orgs, scope).query(command))
                .expectError(AccessDenyException.class).verify();
        verify(scope).query(any());
        verifyNoInteractions(users, orgs);
        assertEquals(1000, input.getPageSize());
    }
}

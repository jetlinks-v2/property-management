package org.jetlinks.pro.property.quality.service;

import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.property.base.command.QueryPropertyUsersCommand;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.auth.DimensionUserBindInfo;
import org.jetlinks.sdk.server.auth.cmd.GetDimensionUserBindCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QualityDirectoryMembersTest {
    private final CommandSupport authentication = mock(CommandSupport.class);
    private final CommandSupport foundation = mock(CommandSupport.class);
    private final QualityDirectory directory = new QualityDirectory() {
        @Override
        protected Mono<CommandSupport> support(String id) {
            return Mono.just(SdkServices.authService.equals(id) ? authentication : foundation);
        }
    };

    private DimensionUserBindInfo binding(String application, List<String> ids) {
        var result = new DimensionUserBindInfo();
        result.setType("business_application"); result.setId(application); result.setUserId(ids);
        return result;
    }

    @Test
    void preselectsOnlyCurrentApplicationMembersThenUsesScopedEmployeeDirectoryInBatches() {
        var ids = IntStream.range(0, 450).mapToObj(i -> "user-" + i).toList();
        var duplicateIds = new ArrayList<>(ids); duplicateIds.add(ids.get(0));
        doAnswer(call -> {
            GetDimensionUserBindCommand command = call.getArgument(0);
            assertEquals(1, command.getDimensions().size());
            assertEquals("business_application", command.getDimensions().get(0).getType());
            assertEquals("app", command.getDimensions().get(0).getId());
            return Flux.just(binding("other-app", List.of("outsider")), binding("app", duplicateIds));
        }).when(authentication).execute(any(GetDimensionUserBindCommand.class));
        var requested = new ArrayList<String>();
        doAnswer(call -> {
            QueryPropertyUsersCommand command = call.getArgument(0);
            assertTrue(command.isEmployeesOnly());
            assertTrue(command.getIds().size() <= 200);
            requested.addAll(command.getIds());
            // 基础命令仍可剔除禁用、非员工或当前调用者不可见的成员。
            return Flux.deferContextual(context -> {
                assertEquals("caller-scope", context.get("test-scope"));
                return Flux.fromIterable(command.getIds()).filter(id -> !"user-3".equals(id)).map(id -> {
                    var person = new PropertyUserInfo(); person.setId(id); return person;
                });
            });
        }).when(foundation).execute(any(QueryPropertyUsersCommand.class));
        StepVerifier.create(directory.employees("app").map(PropertyUserInfo::getId).collectList()
                .contextWrite(context -> context.put("test-scope", "caller-scope")))
            .assertNext(result -> {
                assertEquals(449, result.size()); assertFalse(result.contains("user-3"));
                assertFalse(result.contains("outsider")); assertEquals(ids, requested);
            }).verifyComplete();
        verify(foundation, times(3)).execute(any(QueryPropertyUsersCommand.class));
    }

    @Test
    void emptyMembershipNeverFallsBackToUnrestrictedEmployeeDirectory() {
        doReturn(Flux.just(binding("app", List.of())))
            .when(authentication).execute(any(GetDimensionUserBindCommand.class));
        StepVerifier.create(directory.employees("app")).verifyComplete();
        verifyNoInteractions(foundation);
    }

    @Test
    void membershipFailureIsPropagatedWithoutReturningAnUnrestrictedOrCachedList() {
        var failure = new IllegalStateException("authentication unavailable");
        doReturn(Flux.error(failure)).when(authentication).execute(any(GetDimensionUserBindCommand.class));
        StepVerifier.create(directory.employees("app")).expectErrorMatches(error -> error == failure).verify();
        verifyNoInteractions(foundation);
    }
}

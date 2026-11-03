package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.sdk.server.auth.UserDetail;
import org.jetlinks.sdk.server.auth.UserInfo;
import org.jetlinks.sdk.server.auth.cmd.QueryUserDetailPageCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryListCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 经过真实命令注册与 SDK 命令转换，验证分页/精确复核及账号类型补齐。 */
@SpringBootTest(
        classes = UserCommandSourceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserCommandSourceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Test
    void sourceExecutesExplicitSdkCommandsAndRestoresAccountType() {
        var source = new SaasUserSource();
        StepVerifier.create(source.fetchAll())
                .assertNext(
                        users -> {
                            assertEquals(1, users.size());
                            assertEquals("admin", users.get("admin-id").getType());
                        })
                .verifyComplete();
        StepVerifier.create(source.find("missing"))
                .assertNext(user -> assertTrue(user.isEmpty()))
                .verifyComplete();
        StepVerifier.create(source.find("admin-id"))
                .assertNext(user -> assertEquals("admin", user.orElseThrow().getType()))
                .verifyComplete();
    }

    @Import({OrganizationPersistenceTest.Config.class, Fixture.class})
    static class Config {}

    @CommandService(id = "authService:user", name = "测试用户命令来源")
    public static class Fixture {
        @CommandHandler
        public Mono<PagerResult<UserDetail>> page(QueryUserDetailPageCommand command) {
            var query = command.asQueryParam();
            boolean missing =
                    !query.getTerms().isEmpty()
                            && "missing".equals(query.getTerms().get(0).getValue());
            var user = UserPersistenceTest.user("admin-id");
            user.setType(null);
            return Mono.just(
                    PagerResult.of(missing ? 0 : 1, missing ? List.of() : List.of(user), query));
        }

        @CommandHandler
        public Flux<UserInfo> types(QueryListCommand<UserInfo> command) {
            var query = command.asQueryParam();
            assertFalse(query.isPaging());
            assertEquals("id", query.getTerms().get(0).getColumn());
            var user = new UserInfo();
            user.setId("admin-id");
            user.setType("admin");
            return Flux.just(user);
        }
    }
}

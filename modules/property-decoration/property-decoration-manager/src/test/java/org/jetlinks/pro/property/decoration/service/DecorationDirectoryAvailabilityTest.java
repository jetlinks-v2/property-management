package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.Command;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.core.metadata.FunctionMetadata;
import org.jetlinks.pro.command.CommandSupportManagerProvider;
import org.jetlinks.pro.property.base.command.QueryPropertyHouseholdsCommand;
import org.jetlinks.pro.property.base.command.QueryPropertyDictionaryItemsCommand;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证目录消费方区分旧 RPC 代理的缺失命令、合法空目录与提供方故障。 */
class DecorationDirectoryAvailabilityTest {
    private void withProvider(CommandSupport support, Runnable verify) {
        var previous = CommandSupportManagerProvider.supports.get("propertyService");
        var registration = CommandSupportManagerProvider.supports.register("propertyService", new CommandSupportManagerProvider() {
            @Override public String getProvider() { return "propertyService"; }
            @Override public Mono<? extends CommandSupport> getCommandSupport(String module, Map<String, Object> options) {
                return Mono.just(support);
            }
        });
        try {
            verify.run();
        } finally {
            registration.dispose();
            previous.ifPresent(provider -> CommandSupportManagerProvider.supports.register("propertyService", provider));
        }
    }

    @Test void missingRemoteCommandMustNotBecomeAnEmptyHouseholdList() {
        var executed = new AtomicBoolean();
        var missing = new CommandSupport() {
            @Override @SuppressWarnings("unchecked") public <R> R execute(Command<R> command) {
                executed.set(true);
                return (R) Flux.empty();
            }
            @Override public Mono<FunctionMetadata> getCommandMetadata(String commandId) { return Mono.empty(); }
        };
        withProvider(missing, () -> StepVerifier.create(new DecorationScope().households("project", true))
            .expectErrorSatisfies(error -> assertEquals(503, ((BusinessException) error).getStatus()))
            .verify());
        assertFalse(executed.get());
    }

    @Test void supportedEmptyDirectoryRemainsAValidEmptyResult() {
        var empty = CommandSupport.create(QueryPropertyHouseholdsCommand::new, command -> Flux.empty());
        withProvider(empty, () -> StepVerifier.create(new DecorationScope().households("project", true)).verifyComplete());
    }

    @Test void providerFailurePropagatesWithoutAnEmptyFallback() {
        var failure = new IllegalStateException("directory offline");
        var unavailable = CommandSupport.create(QueryPropertyHouseholdsCommand::new, command -> Flux.error(failure));
        withProvider(unavailable, () -> StepVerifier.create(new DecorationScope().households("project", true))
            .expectErrorMatches(error -> error == failure).verify());
    }
    @Test void missingDictionaryCommandMustFailRatherThanPretendTheHouseTypeDirectoryIsEmpty() {
        var unsupported = CommandSupport.create(QueryPropertyHouseholdsCommand::new, command -> Flux.empty());
        withProvider(unsupported, () -> StepVerifier.create(new DecorationScope().dictionaryItems("house-type"))
            .expectErrorSatisfies(e -> assertEquals(503, ((BusinessException) e).getStatus())).verify());
    }
    @Test void dictionaryPayloadAndAuthenticationReachThePublicProvider() {
        var support = CommandSupport.create(QueryPropertyDictionaryItemsCommand::new, command ->
            org.hswebframework.web.authorization.Authentication.currentReactive().flatMapMany(auth -> {
                assertEquals("house-type", command.getTypeCode()); assertEquals("reader", auth.getUser().getId());
                return Flux.just(new PropertyDictionaryItemInfo("residential", "住宅", 1L));
            }));
        var user = new org.hswebframework.web.authorization.simple.SimpleUser(); user.setId("reader");
        var authentication = new org.hswebframework.web.authorization.simple.SimpleAuthentication(); authentication.setUser(user);
        withProvider(support, () -> StepVerifier.create(new DecorationScope().dictionaryItems("house-type")
                .contextWrite(reactor.util.context.Context.of(org.hswebframework.web.authorization.Authentication.class, authentication)))
            .expectNext(new PropertyDictionaryItemInfo("residential", "住宅", 1L)).verifyComplete());
    }
}

package org.jetlinks.pro.property.base.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.ResourceAction;
import org.jetlinks.pro.property.base.entity.PropertyDictionaryTypeEntity;
import org.jetlinks.pro.property.base.entity.PropertyDictionaryItemEntity;
import org.jetlinks.pro.property.base.service.PropertyDictionaryService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

/** 全局字典维护使用菜单动作权限，不接受项目参数改变字典范围或维护权限。 */
@RestController
@RequestMapping("/property/dictionary")
@Resource(id = "property-dictionary", name = "物业字典管理")
@Authorize
@RequiredArgsConstructor
public class PropertyDictionaryController {
    private final PropertyDictionaryService dictionaries;

    @GetMapping("/_types")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertyDictionaryTypeEntity>> types() {
        return dictionaries.types();
    }

    @PostMapping("/_types")
    @ResourceAction(id = "save", name = "保存")
    public Mono<PropertyDictionaryTypeEntity> createType(
            @RequestBody PropertyDictionaryService.TypeRequest input) {
        return dictionaries.createType(input);
    }

    @PutMapping("/_types/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<PropertyDictionaryTypeEntity> updateType(
            @PathVariable String id, @RequestBody PropertyDictionaryService.TypeRequest input) {
        return dictionaries.updateType(id, input);
    }

    @DeleteMapping("/_types/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> deleteType(@PathVariable String id, @RequestParam long version) {
        return dictionaries.deleteType(id, version);
    }

    @GetMapping("/{code}/_items")
    @ResourceAction(id = "query", name = "查询")
    public Mono<List<PropertyDictionaryItemEntity>> items(@PathVariable String code) {
        return dictionaries.items(code);
    }

    @PostMapping("/{code}/_items")
    @ResourceAction(id = "save", name = "保存")
    public Mono<PropertyDictionaryItemEntity> createItem(
            @PathVariable String code, @RequestBody PropertyDictionaryService.ItemRequest input) {
        return dictionaries.createItem(code, input);
    }

    @PutMapping("/items/{id}")
    @ResourceAction(id = "save", name = "保存")
    public Mono<PropertyDictionaryItemEntity> updateItem(
            @PathVariable String id, @RequestBody PropertyDictionaryService.ItemRequest input) {
        return dictionaries.updateItem(id, input);
    }

    @DeleteMapping("/items/{id}")
    @ResourceAction(id = "delete", name = "删除")
    public Mono<Void> deleteItem(@PathVariable String id, @RequestParam long version) {
        return dictionaries.deleteItem(id, version);
    }
}

package dev.isxander.yacl3.config.v2.impl;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.config.v2.api.*;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.OptionStorage;
import dev.isxander.yacl3.config.v2.impl.autogen.OptionFactoryRegistry;
import dev.isxander.yacl3.platform.YACLPlatform;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.Validate;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public class ConfigClassHandlerImpl<T> implements ConfigClassHandler<T> {
    private final Class<T> configClass;
    private final ResourceLocation id;
    private final boolean supportsAutoGen;
    private final ConfigSerializer<T> serializer;
    private final ConfigField<?>[] fields;

    private final T instance, defaults;

    public ConfigClassHandlerImpl(Class<T> configClass, ResourceLocation id, Function<ConfigClassHandler<T>, ConfigSerializer<T>> serializerFactory, boolean autoGen) {
        this.configClass = configClass;
        this.id = id;
        this.supportsAutoGen = YACLPlatform.getEnvironment().isClient() && autoGen;

        try {
            Constructor<T> constructor = configClass.getDeclaredConstructor();
            this.instance = constructor.newInstance();
            this.defaults = constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create instance of config class '%s' with no-args constructor.".formatted(configClass.getName()), e);
        }

        this.fields = Arrays.stream(configClass.getDeclaredFields())
                .peek(field -> field.setAccessible(true))
                .filter(field -> field.isAnnotationPresent(SerialEntry.class) || field.isAnnotationPresent(AutoGen.class))
                .map(field -> new ConfigFieldImpl<>(new ReflectionFieldAccess<>(field, instance), new ReflectionFieldAccess<>(field, defaults), this, field.getAnnotation(SerialEntry.class), field.getAnnotation(AutoGen.class)))
                .toArray(ConfigField[]::new);
        this.serializer = serializerFactory.apply(this);
    }

    @Override
    public T instance() {
        return this.instance;
    }

    @Override
    public T defaults() {
        return this.defaults;
    }

    @Override
    public Class<T> configClass() {
        return this.configClass;
    }

    @Override
    public ConfigField<?>[] fields() {
        return this.fields;
    }

    @Override
    public ResourceLocation id() {
        return this.id;
    }

    @Override
    public boolean supportsAutoGen() {
        return this.supportsAutoGen;
    }

    @Override
    public YetAnotherConfigLib generateGui() {
        Validate.isTrue(supportsAutoGen(), "Auto GUI generation is not supported for this config class. You either need to enable it in the builder or you are attempting to create a GUI in a dedicated server environment.");

        OptionStorageImpl storage = new OptionStorageImpl();
        Map<String, CategoryAndGroups> categories = new LinkedHashMap<>();
        for (ConfigField<?> configField : fields()) {
            configField.autoGen().ifPresent(autoGen -> {
                CategoryAndGroups groups = categories.computeIfAbsent(
                        autoGen.category(),
                        k -> new CategoryAndGroups(
                                ConfigCategory.createBuilder()
                                        .name(Component.translatable("yacl3.config.%s.category.%s".formatted(id().toString(), k))),
                                new LinkedHashMap<>()
                        )
                );
                OptionAddable group = groups.groups().computeIfAbsent(autoGen.group().orElse(""), k -> {
                    if (k.isEmpty())
                        return groups.category();
                    return OptionGroup.createBuilder()
                            .name(Component.translatable("yacl3.config.%s.category.%s.group.%s".formatted(id().toString(), autoGen.category(), k)));
                });

                Option<?> option = createOption(configField, storage);
                storage.putOption(configField.access().name(), option);
                group.option(option);
            });
        }
        categories.values().forEach(CategoryAndGroups::finaliseGroups);

        YetAnotherConfigLib.Builder yaclBuilder = YetAnotherConfigLib.createBuilder()
                .save(this.serializer()::serialize)
                .title(Component.translatable("yacl3.config.%s.title".formatted(this.id().toString())));
        categories.values().forEach(category -> yaclBuilder.category(category.category().build()));

        return yaclBuilder.build();
    }

    private <U> Option<U> createOption(ConfigField<U> configField, OptionStorage storage) {
        return OptionFactoryRegistry.createOption(((ReflectionFieldAccess<?>) configField.access()).field(), configField, storage)
                .orElseThrow(() -> new IllegalStateException("Failed to create option for field %s".formatted(configField.access().name())));
    }

    @Override
    public ConfigSerializer<T> serializer() {
        return this.serializer;
    }

    public static class BuilderImpl<T> implements Builder<T> {
        private final Class<T> configClass;
        private ResourceLocation id;
        private Function<ConfigClassHandler<T>, ConfigSerializer<T>> serializerFactory;
        private boolean autoGen;

        public BuilderImpl(Class<T> configClass) {
            this.configClass = configClass;
        }

        @Override
        public Builder<T> id(ResourceLocation id) {
            this.id = id;
            return this;
        }

        @Override
        public Builder<T> serializer(Function<ConfigClassHandler<T>, ConfigSerializer<T>> serializerFactory) {
            this.serializerFactory = serializerFactory;
            return this;
        }

        @Override
        public Builder<T> autoGen(boolean autoGen) {
            this.autoGen = autoGen;
            return this;
        }

        @Override
        public ConfigClassHandler<T> build() {
            return new ConfigClassHandlerImpl<>(configClass, id, serializerFactory, autoGen);
        }
    }

    private record CategoryAndGroups(ConfigCategory.Builder category, Map<String, OptionAddable> groups) {
        private void finaliseGroups() {
            groups.forEach((name, group) -> {
                if (group instanceof OptionGroup.Builder groupBuilder) {
                    category.group(groupBuilder.build());
                }
            });
        }
    }
}

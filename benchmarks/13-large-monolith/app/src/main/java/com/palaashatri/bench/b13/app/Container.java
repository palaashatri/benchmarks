package com.palaashatri.bench.b13.app;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A small constructor-injection container. Beans are declared with {@link Bean}, their
 * dependencies with {@link Dep} on constructor parameters. Every bean is instantiated once,
 * dependencies first. Duplicate names, missing dependencies and cycles fail startup.
 */
public final class Container {
    private final Map<String, Class<?>> types = new HashMap<>();
    private final Map<String, Object> instances = new HashMap<>();

    private Container() { }

    public static Container wire(String[] classNames) throws ReflectiveOperationException {
        Container container = new Container();
        for (String className : classNames) {
            Class<?> type = Class.forName(className);
            Bean bean = type.getAnnotation(Bean.class);
            if (bean == null) {
                throw new IllegalStateException(className + " is not annotated with @Bean");
            }
            if (container.types.putIfAbsent(bean.value(), type) != null) {
                throw new IllegalStateException("duplicate bean " + bean.value());
            }
        }
        for (String id : container.types.keySet()) {
            container.resolve(id, new LinkedHashSet<>());
        }
        return container;
    }

    /** Number of beans that were instantiated; equals the number declared when wiring succeeds. */
    public int instanceCount() {
        return instances.size();
    }

    public Component component(String id) {
        Object bean = instances.get(id);
        if (!(bean instanceof Component component)) {
            throw new IllegalStateException("no component bean named " + id);
        }
        return component;
    }

    private Object resolve(String id, Set<String> path) throws ReflectiveOperationException {
        Object existing = instances.get(id);
        if (existing != null) {
            return existing;
        }
        if (!path.add(id)) {
            throw new IllegalStateException("dependency cycle through " + id);
        }
        Class<?> type = types.get(id);
        if (type == null) {
            throw new IllegalStateException("no bean named " + id);
        }
        Constructor<?>[] constructors = type.getConstructors();
        if (constructors.length != 1) {
            throw new IllegalStateException(id + " must declare exactly one public constructor");
        }
        Annotation[][] parameters = constructors[0].getParameterAnnotations();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            arguments[i] = resolve(dependencyName(parameters[i], id), path);
        }
        Object instance = constructors[0].newInstance(arguments);
        path.remove(id);
        instances.put(id, instance);
        return instance;
    }

    private static String dependencyName(Annotation[] annotations, String owner) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Dep dep) {
                return dep.value();
            }
        }
        throw new IllegalStateException(owner + " has a constructor parameter without @Dep");
    }
}

package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.annotation.BloomAdd;
import io.github.ncibioussama.bloomfilter.annotation.BloomCheck;
import io.github.ncibioussama.bloomfilter.annotation.BloomFilterMissException;
import io.github.ncibioussama.bloomfilter.annotation.OnMiss;
import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodClassKey;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Implements {@link BloomCheck} and {@link BloomAdd}.
 */
final class BloomFilterMethodInterceptor implements MethodInterceptor {

    private static final Log log = LogFactory.getLog(BloomFilterMethodInterceptor.class);

    private final ObjectProvider<BloomFilterRegistry> registryProvider;
    private final BloomKeyExpressionEvaluator evaluator = new BloomKeyExpressionEvaluator();
    private final Map<MethodClassKey, Operations> operations = new ConcurrentHashMap<>();
    private volatile BloomFilterRegistry registry;

    private record Operations(Method method, BloomCheck check, BloomAdd add) {
    }

    BloomFilterMethodInterceptor(ObjectProvider<BloomFilterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object target = invocation.getThis();
        Class<?> targetClass = target != null
                ? AopUtils.getTargetClass(target) : invocation.getMethod().getDeclaringClass();
        Operations ops = operations.computeIfAbsent(new MethodClassKey(invocation.getMethod(), targetClass),
                key -> resolve(invocation.getMethod(), targetClass));
        if (ops.check() == null && ops.add() == null) {
            return invocation.proceed();
        }
        Object[] args = invocation.getArguments();

        BloomFilter checked = null;
        if (ops.check() != null) {
            BloomCheck check = ops.check();
            Object key = evaluator.evaluate(check.key(), ops.method(), args, target, targetClass,
                    BloomKeyExpressionEvaluator.NO_RESULT);
            if (key != null) {
                checked = registry().get(check.filter());
                if (!checked.mightContain(key)) {
                    return onMiss(check, ops.method());
                }
            }
        }

        Object result = invocation.proceed();

        if (checked != null && ops.check().trackFalsePositives() && isEmptyResult(result)) {
            checked.recordFalsePositive();
        }
        if (ops.add() != null) {
            add(ops, args, target, targetClass, result);
        }
        return result;
    }

    private void add(Operations ops, Object[] args, Object target, Class<?> targetClass, Object result) {
        Object value = result instanceof Optional<?> optional ? optional.orElse(null) : result;
        Class<?> returnType = ops.method().getReturnType();
        boolean isVoid = returnType == void.class || returnType == Void.class;
        if (value == null && !isVoid) {
            return; // nothing was created
        }
        BloomAdd add = ops.add();
        BloomFilter filter = registry().get(add.filter());
        Object key = evaluator.evaluate(add.key(), ops.method(), args, target, targetClass, value);
        if (key == null) {
            return;
        }
        try {
            if (key instanceof Iterable<?> keys) {
                filter.putAll(keys);
            } else if (key instanceof Object[] array) {
                filter.putAll(Arrays.asList(array));
            } else {
                filter.put(key);
            }
        } catch (RuntimeException e) {
            // The business operation succeeded; do not fail it. The filter is now in bypass mode.
            log.warn("Could not add key to Bloom filter '" + add.filter() + "': " + e.getMessage());
        }
    }

    private Operations resolve(Method method, Class<?> targetClass) {
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        BloomCheck check = find(specific, method, BloomCheck.class);
        BloomAdd add = find(specific, method, BloomAdd.class);
        if (check != null && check.onMiss() == OnMiss.RETURN_EMPTY) {
            Class<?> type = specific.getReturnType();
            if (type.isPrimitive() && type != boolean.class && type != void.class) {
                throw new IllegalStateException("@BloomCheck on " + specific + " returns " + type
                        + ", which has no empty value. Use onMiss = OnMiss.THROW or return an Optional.");
            }
        }
        return new Operations(specific, check, add);
    }

    private static <A extends java.lang.annotation.Annotation> A find(Method specific, Method original,
                                                                      Class<A> type) {
        A annotation = AnnotatedElementUtils.findMergedAnnotation(specific, type);
        return annotation != null ? annotation : AnnotatedElementUtils.findMergedAnnotation(original, type);
    }

    private BloomFilterRegistry registry() {
        BloomFilterRegistry current = registry;
        if (current == null) {
            current = registryProvider.getObject();
            registry = current;
        }
        return current;
    }

    private static Object onMiss(BloomCheck check, Method method) throws Exception {
        if (check.onMiss() == OnMiss.THROW) {
            throw new BloomFilterMissException(check.filter());
        }
        return emptyValue(method.getReturnType());
    }

    static Object emptyValue(Class<?> type) throws Exception {
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.FALSE;
        }
        if (type == List.class || type == Collection.class || type == Iterable.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type == Stream.class) {
            return Stream.empty();
        }
        if (type == CompletableFuture.class || type == CompletionStage.class) {
            return CompletableFuture.completedFuture(null);
        }
        if (type.getName().equals("reactor.core.publisher.Mono") || type.getName().equals("reactor.core.publisher.Flux")) {
            return type.getMethod("empty").invoke(null);
        }
        return null;
    }

    static boolean isEmptyResult(Object result) {
        if (result == null || Boolean.FALSE.equals(result)) {
            return true;
        }
        if (result instanceof Optional<?> optional) {
            return optional.isEmpty();
        }
        if (result instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (result instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }
}

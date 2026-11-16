package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.annotation.BloomAdd;
import io.github.ncibioussama.bloomfilter.annotation.BloomCheck;
import java.lang.reflect.Method;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Matches methods annotated with {@link BloomCheck} or {@link BloomAdd}, including annotations declared
 * on interface methods.
 */
final class BloomFilterAnnotationPointcut extends StaticMethodMatcherPointcut {

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        if (isAnnotated(method)) {
            return true;
        }
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        return specific != method && isAnnotated(specific);
    }

    static boolean isAnnotated(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, BloomCheck.class)
                || AnnotatedElementUtils.hasAnnotation(method, BloomAdd.class);
    }
}

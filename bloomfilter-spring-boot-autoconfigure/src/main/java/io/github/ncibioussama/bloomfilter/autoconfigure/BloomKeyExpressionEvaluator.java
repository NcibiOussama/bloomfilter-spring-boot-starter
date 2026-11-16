package io.github.ncibioussama.bloomfilter.autoconfigure;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.context.expression.CachedExpressionEvaluator;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;

/**
 * Evaluates (and caches) the SpEL key expressions of the annotations. Expressions come from annotation
 * attributes written by the developer, never from request data.
 */
final class BloomKeyExpressionEvaluator extends CachedExpressionEvaluator {

    static final Object NO_RESULT = new Object();

    private final Map<ExpressionKey, Expression> cache = new ConcurrentHashMap<>(64);

    Object evaluate(String expression, Method method, Object[] args, Object target, Class<?> targetClass,
                    Object result) {
        MethodBasedEvaluationContext context =
                new MethodBasedEvaluationContext(target, method, args, getParameterNameDiscoverer());
        if (result != NO_RESULT) {
            context.setVariable("result", result);
        }
        try {
            return getExpression(cache, new AnnotatedElementKey(method, targetClass), expression).getValue(context);
        } catch (EvaluationException e) {
            throw new IllegalStateException("Cannot evaluate Bloom filter key expression '" + expression + "' on "
                    + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + "(). If parameter names are unavailable, compile with '-parameters' or use #p0, #p1...", e);
        }
    }
}

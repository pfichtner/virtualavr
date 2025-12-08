package com.github.pfichtner.testcontainers.virtualavr.util;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GracefulCloseProxy implements InvocationHandler {

	private static final Logger logger = LoggerFactory.getLogger(GracefulCloseProxy.class);

	private static final Map<Class<?>, Object> WRAPPER_DEFAULTS = Map.of(Boolean.class, Boolean.FALSE, Byte.class,
			(byte) 0, Short.class, (short) 0, Integer.class, 0, Long.class, 0L, Float.class, 0f, Double.class, 0d,
			Character.class, '\0');

	private final Object target;
	private volatile boolean closingGracefully;

	public static <T> T wrapWithGracefulClose(T target, Class<T> type) {
		Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				new GracefulCloseProxy(target));
		return type.cast(proxy);
	}

	public GracefulCloseProxy(Object target) {
		this.target = target;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getName().equals("close") && method.getParameterCount() == 0) {
			closingGracefully = true;
			return method.invoke(target, args);
		}

		try {
			return method.invoke(target, args);
		} catch (Throwable t) {
			if (!closingGracefully) {
				throw t.getCause() == null ? t : t.getCause();
			}
			logger.debug("Ignored exception during graceful close: {}", t.getMessage());
			return method.getReturnType().isInstance(proxy) ? proxy : defaultValue(method.getReturnType());
		}
	}

	private static Object defaultValue(Class<?> type) {
		return type == void.class //
				? null //
				: type.isPrimitive() //
						? Array.get(Array.newInstance(type, 1), 0) //
						: WRAPPER_DEFAULTS.getOrDefault(type, null);
	}

}

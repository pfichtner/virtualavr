package com.github.pfichtner.testcontainers.virtualavr.util;

import static com.github.pfichtner.testcontainers.virtualavr.util.GracefulCloseProxy.wrapWithGracefulClose;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;

class GracefulCloseProxyTest {

	VirtualAvrConnection sut = wrapWithGracefulClose(mock(VirtualAvrConnection.class), VirtualAvrConnection.class);

	@Test
	void beforeCloseExceptionIsFired() {
		assertThatException().isThrownBy(sut::pause).isInstanceOf(IllegalStateException.class)
				.withMessage("Simulated exception from pause");
	}

	@Test
	void afterCloseExceptionIsSwallowed() {
		sut.close();
		assertThatNoException().isThrownBy(sut::pause);
	}

	@Test
	void wrapperReturnsItself() {
		sut.close();
		assertThat(sut.pause()).isSameAs(sut);
	}

	// migrate to Mockito if more tests are needed
	private VirtualAvrConnection mock(Class<VirtualAvrConnection> clazz) {
		return clazz.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] { clazz },
				(InvocationHandler) (__p, method, __a) -> {
					if (method.getName().equals("close")) {
						return null;
					}
					throw new IllegalStateException("Simulated exception from " + method.getName());
				}));
	}

}

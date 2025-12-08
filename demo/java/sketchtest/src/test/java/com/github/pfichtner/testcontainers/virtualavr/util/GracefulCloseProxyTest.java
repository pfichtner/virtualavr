package com.github.pfichtner.testcontainers.virtualavr.util;

import static com.github.pfichtner.testcontainers.virtualavr.util.GracefulCloseProxy.wrapWithGracefulClose;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;

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
		assertThatNoException().isThrownBy(closedSut()::pause);
	}

	@Test
	void wrapperReturnsItself() {
		assertThat(closedSut().pause()).isSameAs(sut);
	}

	@Test
	void wrapperReturnsPrimitiveDefaults() {
		assertThat(closedSut().isConnected()).isFalse();
	}

	private VirtualAvrConnection closedSut() {
		sut.close();
		return sut;
	}

	// migrate to Mockito if more tests are needed
	VirtualAvrConnection mock(Class<VirtualAvrConnection> clazz) {
		return clazz.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] { clazz }, (__p, m, __a) -> {
			if (m.getName().equals("close")) {
				return null;
			}
			throw new IllegalStateException("Simulated exception from " + m.getName());
		}));
	}

}

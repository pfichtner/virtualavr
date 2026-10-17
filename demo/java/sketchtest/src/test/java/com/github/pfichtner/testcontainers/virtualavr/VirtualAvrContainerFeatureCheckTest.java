package com.github.pfichtner.testcontainers.virtualavr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatException;

import java.io.File;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("resource")
class VirtualAvrContainerFeatureCheckTest {

	@Test
	void supportedFeaturePasses() {
		VirtualAvrContainer<?> container = new TestContainer().withSupported("VIRTUALDEVICE", "WS_TOKEN", "WATCH")
				.withWatch();
		assertThatCode(container::verifyImageFeaturesSupportsRequestedFeatures).doesNotThrowAnyException();
	}

	@Test
	void unsupportedFeatureFailsEvenWithoutStrictCheck() {
		VirtualAvrContainer<?> container = new TestContainer().withSupported("EEPROM_FILE").withWatch();
		assertThatException().isThrownBy(container::verifyImageFeaturesSupportsRequestedFeatures)
				.isInstanceOf(IllegalStateException.class) //
				.withMessageContaining("WATCH");
	}

	@Test
	void eepromFeatureRequiresEepromFileEnvVar(@TempDir File tmpDir) {
		TestContainer container = new TestContainer().withSupported("WATCH");
		container.withEepromFile(new File(tmpDir, "eeprom.bin"));
		assertThatException().isThrownBy(container::verifyImageFeaturesSupportsRequestedFeatures)
				.isInstanceOf(IllegalStateException.class) //
				.withMessageContaining("EEPROM_FILE");
	}

	@Test
	void unknownSupportWarnsWithoutStrictCheck() {
		VirtualAvrContainer<?> container = new TestContainer().withoutSupportInformation().withStrictFeatureCheck(false)
				.withWatch();
		assertThatCode(container::verifyImageFeaturesSupportsRequestedFeatures).doesNotThrowAnyException();
	}

	@Test
	void unknownSupportFailsWithStrictCheck() {
		VirtualAvrContainer<?> container = new TestContainer().withoutSupportInformation().withStrictFeatureCheck(true)
				.withWatch();
		assertThatException().isThrownBy(container::verifyImageFeaturesSupportsRequestedFeatures)
				.isInstanceOf(IllegalStateException.class) //
				.withMessageContaining("WATCH");
	}

	@Test
	void parsesSupportedEnvVarsIgnoringCommentsAndBlankLines() {
		assertThat(VirtualAvrContainer.parseSupportedEnvVars("EEPROM_FILE\n\n# a comment\n  WATCH  \n")) //
				.containsExactlyInAnyOrder("EEPROM_FILE", "WATCH");
	}

	static class TestContainer extends VirtualAvrContainer<TestContainer> {

		private Set<String> supported = Set.of();

		TestContainer withSupported(String... envVars) {
			this.supported = Set.of(envVars);
			return self();
		}

		TestContainer withoutSupportInformation() {
			this.supported = Set.of();
			return self();
		}

		@Override
		Set<String> imageSupportedEnvVars() {
			return supported;
		}

	}

}

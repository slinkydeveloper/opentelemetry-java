/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static io.opentelemetry.sdk.testing.assertj.metrics.MetricAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BoundDoubleUpDownCounter;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.common.Labels;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.internal.TestClock;
import io.opentelemetry.sdk.metrics.StressTestRunner.OperationUpdater;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DoubleUpDownCounterSdk}. */
class DoubleUpDownCounterSdkTest {
  private static final long SECOND_NANOS = 1_000_000_000;
  private static final Resource RESOURCE =
      Resource.create(Attributes.of(stringKey("resource_key"), "resource_value"));
  private static final InstrumentationLibraryInfo INSTRUMENTATION_LIBRARY_INFO =
      InstrumentationLibraryInfo.create(DoubleUpDownCounterSdkTest.class.getName(), null);
  private final TestClock testClock = TestClock.create();
  private final SdkMeterProvider sdkMeterProvider =
      SdkMeterProvider.builder().setClock(testClock).setResource(RESOURCE).build();
  private final Meter sdkMeter = sdkMeterProvider.get(getClass().getName());

  @Test
  void add_PreventNullLabels() {
    assertThatThrownBy(
            () -> sdkMeter.doubleUpDownCounterBuilder("testUpDownCounter").build().add(1.0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("labels");
  }

  @Test
  void bound_PreventNullLabels() {
    assertThatThrownBy(
            () -> sdkMeter.doubleUpDownCounterBuilder("testUpDownCounter").build().bind(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("labels");
  }

  @Test
  void collectMetrics_NoRecords() {
    DoubleUpDownCounter doubleUpDownCounter =
        sdkMeter.doubleUpDownCounterBuilder("testUpDownCounter").build();
    BoundDoubleUpDownCounter bound = doubleUpDownCounter.bind(Labels.of("foo", "bar"));
    try {
      assertThat(sdkMeterProvider.collectAllMetrics()).isEmpty();
    } finally {
      bound.unbind();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void collectMetrics_WithEmptyLabel() {
    DoubleUpDownCounter doubleUpDownCounter =
        sdkMeter
            .doubleUpDownCounterBuilder("testUpDownCounter")
            .setDescription("description")
            .setUnit("ms")
            .build();
    testClock.advanceNanos(SECOND_NANOS);
    doubleUpDownCounter.add(12d, Labels.empty());
    doubleUpDownCounter.add(12d);
    assertThat(sdkMeterProvider.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasResource(RESOURCE)
                    .hasInstrumentationLibrary(INSTRUMENTATION_LIBRARY_INFO)
                    .hasName("testUpDownCounter")
                    .hasDescription("description")
                    .hasUnit("ms")
                    .hasDoubleSum()
                    .isNotMonotonic()
                    .isCumulative()
                    .points()
                    .satisfiesExactly(
                        point ->
                            assertThat(point)
                                .hasStartEpochNanos(testClock.now() - SECOND_NANOS)
                                .hasEpochNanos(testClock.now())
                                .hasAttributes(Attributes.empty())
                                .hasValue(24)));
  }

  @Test
  @SuppressWarnings("unchecked")
  void collectMetrics_WithMultipleCollects() {
    long startTime = testClock.now();
    DoubleUpDownCounter doubleUpDownCounter =
        sdkMeter.doubleUpDownCounterBuilder("testUpDownCounter").build();
    BoundDoubleUpDownCounter bound = doubleUpDownCounter.bind(Labels.of("K", "V"));
    try {
      // Do some records using bounds and direct calls and bindings.
      doubleUpDownCounter.add(12.1d, Labels.empty());
      bound.add(123.3d);
      doubleUpDownCounter.add(21.4d, Labels.empty());
      // Advancing time here should not matter.
      testClock.advanceNanos(SECOND_NANOS);
      bound.add(321.5d);
      doubleUpDownCounter.add(111.1d, Labels.of("K", "V"));
      assertThat(sdkMeterProvider.collectAllMetrics())
          .satisfiesExactly(
              metric ->
                  assertThat(metric)
                      .hasResource(RESOURCE)
                      .hasInstrumentationLibrary(INSTRUMENTATION_LIBRARY_INFO)
                      .hasName("testUpDownCounter")
                      .hasDoubleSum()
                      .isNotMonotonic()
                      .isCumulative()
                      .points()
                      .allSatisfy(
                          point ->
                              assertThat(point)
                                  .hasStartEpochNanos(startTime)
                                  .hasEpochNanos(testClock.now()))
                      .satisfiesExactlyInAnyOrder(
                          point ->
                              assertThat(point).hasAttributes(Attributes.empty()).hasValue(33.5),
                          point ->
                              assertThat(point)
                                  .hasValue(555.9)
                                  .hasAttributes(Attributes.of(stringKey("K"), "V"))));

      // Repeat to prove we keep previous values.
      testClock.advanceNanos(SECOND_NANOS);
      bound.add(222d);
      doubleUpDownCounter.add(11d, Labels.empty());
      assertThat(sdkMeterProvider.collectAllMetrics())
          .satisfiesExactly(
              metric ->
                  assertThat(metric)
                      .hasResource(RESOURCE)
                      .hasInstrumentationLibrary(INSTRUMENTATION_LIBRARY_INFO)
                      .hasName("testUpDownCounter")
                      .hasDoubleSum()
                      .isNotMonotonic()
                      .isCumulative()
                      .points()
                      .allSatisfy(
                          point ->
                              assertThat(point)
                                  .hasStartEpochNanos(startTime)
                                  .hasEpochNanos(testClock.now()))
                      .satisfiesExactlyInAnyOrder(
                          point ->
                              assertThat(point).hasAttributes(Attributes.empty()).hasValue(44.5),
                          point ->
                              assertThat(point)
                                  .hasAttributes(Attributes.of(stringKey("K"), "V"))
                                  .hasValue(777.9)));
    } finally {
      bound.unbind();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void stressTest() {
    final DoubleUpDownCounter doubleUpDownCounter =
        sdkMeter.doubleUpDownCounterBuilder("testUpDownCounter").build();

    StressTestRunner.Builder stressTestBuilder =
        StressTestRunner.builder()
            .setInstrument((DoubleUpDownCounterSdk) doubleUpDownCounter)
            .setCollectionIntervalMs(100);

    for (int i = 0; i < 4; i++) {
      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              1_000, 2, new OperationUpdaterDirectCall(doubleUpDownCounter, "K", "V")));
      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              1_000,
              2,
              new OperationUpdaterWithBinding(doubleUpDownCounter.bind(Labels.of("K", "V")))));
    }

    stressTestBuilder.build().run();
    assertThat(sdkMeterProvider.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasResource(RESOURCE)
                    .hasInstrumentationLibrary(INSTRUMENTATION_LIBRARY_INFO)
                    .hasName("testUpDownCounter")
                    .hasDoubleSum()
                    .isCumulative()
                    .isNotMonotonic()
                    .points()
                    .satisfiesExactlyInAnyOrder(
                        point ->
                            assertThat(point)
                                .hasStartEpochNanos(testClock.now())
                                .hasEpochNanos(testClock.now())
                                .hasValue(80_000)
                                .attributes()
                                .hasSize(1)
                                .containsEntry("K", "V")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void stressTest_WithDifferentLabelSet() {
    final String[] keys = {"Key_1", "Key_2", "Key_3", "Key_4"};
    final String[] values = {"Value_1", "Value_2", "Value_3", "Value_4"};
    final DoubleUpDownCounter doubleUpDownCounter =
        sdkMeter.doubleUpDownCounterBuilder("testUpDownCounter").build();

    StressTestRunner.Builder stressTestBuilder =
        StressTestRunner.builder()
            .setInstrument((DoubleUpDownCounterSdk) doubleUpDownCounter)
            .setCollectionIntervalMs(100);

    for (int i = 0; i < 4; i++) {
      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              2_000, 1, new OperationUpdaterDirectCall(doubleUpDownCounter, keys[i], values[i])));

      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              2_000,
              1,
              new OperationUpdaterWithBinding(
                  doubleUpDownCounter.bind(Labels.of(keys[i], values[i])))));
    }

    stressTestBuilder.build().run();
    assertThat(sdkMeterProvider.collectAllMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasResource(RESOURCE)
                    .hasInstrumentationLibrary(INSTRUMENTATION_LIBRARY_INFO)
                    .hasName("testUpDownCounter")
                    .hasDoubleSum()
                    .isCumulative()
                    .isNotMonotonic()
                    .points()
                    .allSatisfy(
                        point ->
                            assertThat(point)
                                .hasStartEpochNanos(testClock.now())
                                .hasEpochNanos(testClock.now())
                                .hasValue(40_000))
                    .extracting(point -> point.getAttributes())
                    .containsExactlyInAnyOrder(
                        Attributes.of(stringKey(keys[0]), values[0]),
                        Attributes.of(stringKey(keys[1]), values[1]),
                        Attributes.of(stringKey(keys[2]), values[2]),
                        Attributes.of(stringKey(keys[3]), values[3])));
  }

  private static class OperationUpdaterWithBinding extends OperationUpdater {
    private final BoundDoubleUpDownCounter boundDoubleUpDownCounter;

    private OperationUpdaterWithBinding(BoundDoubleUpDownCounter boundDoubleUpDownCounter) {
      this.boundDoubleUpDownCounter = boundDoubleUpDownCounter;
    }

    @Override
    void update() {
      boundDoubleUpDownCounter.add(9.0);
    }

    @Override
    void cleanup() {
      boundDoubleUpDownCounter.unbind();
    }
  }

  private static class OperationUpdaterDirectCall extends OperationUpdater {

    private final DoubleUpDownCounter doubleUpDownCounter;
    private final String key;
    private final String value;

    private OperationUpdaterDirectCall(
        DoubleUpDownCounter doubleUpDownCounter, String key, String value) {
      this.doubleUpDownCounter = doubleUpDownCounter;
      this.key = key;
      this.value = value;
    }

    @Override
    void update() {
      doubleUpDownCounter.add(11.0, Labels.of(key, value));
    }

    @Override
    void cleanup() {}
  }
}

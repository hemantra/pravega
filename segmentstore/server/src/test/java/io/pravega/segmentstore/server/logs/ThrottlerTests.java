/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.logs;

import io.pravega.segmentstore.server.SegmentStoreMetrics;
import io.pravega.test.common.AssertExtensions;
import io.pravega.test.common.TestUtils;
import io.pravega.test.common.ThreadPooledTestSuite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.Cleanup;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.val;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@link Throttler} class.
 */
public class ThrottlerTests extends ThreadPooledTestSuite {
    private static final int CONTAINER_ID = 1;
    private static final ThrottlerCalculator.ThrottlerName THROTTLER_NAME = ThrottlerCalculator.ThrottlerName.Cache;
    private static final int MAX_THROTTLE_MILLIS = ThrottlerCalculator.MAX_DELAY_MILLIS;
    private static final int NON_MAX_THROTTLE_MILLIS = MAX_THROTTLE_MILLIS - 1;
    private static final int TIMEOUT_MILLIS = 10000;
    private static final int SHORT_TIMEOUT_MILLIS = 50;
    private SegmentStoreMetrics.OperationProcessor metrics;

    @Override
    protected int getThreadPoolSize() {
        return 1;
    }

    @Before
    public void setUp() {
        this.metrics = new SegmentStoreMetrics.OperationProcessor(CONTAINER_ID);
    }

    @After
    public void tearDown() {
        this.metrics.close();
    }

    /**
     * Tests the {@link Throttler#isThrottlingRequired()} method.
     */
    @Test
    public void testThrottlingRequired() {
        val delays = Collections.<Integer>synchronizedList(new ArrayList<>());
        val calculator = new TestCalculatorThrottler(THROTTLER_NAME);
        @Cleanup
        Throttler t = new AutoCompleteTestThrottler(CONTAINER_ID, wrap(calculator), executorService(), metrics, delays::add);

        calculator.setThrottlingRequired(false);
        Assert.assertFalse("Not expecting any throttling to be required.", t.isThrottlingRequired());
        calculator.setThrottlingRequired(true);
        Assert.assertTrue("Expected throttling to be required.", t.isThrottlingRequired());

        Assert.assertFalse("Unexpected value from isClosed() before closing.", t.isClosed());
        t.close();
        Assert.assertTrue("Unexpected value from isClosed() after closing.", t.isClosed());
    }

    /**
     * Tests the case when {@link ThrottlerCalculator#getThrottlingDelay()} returns a value which does not warrant repeated
     * delays (i.e., not maximum delay)
     */
    @Test
    public void testSingleDelay() throws Exception {
        val delays = Collections.<Integer>synchronizedList(new ArrayList<>());
        val calculator = new TestCalculatorThrottler(THROTTLER_NAME);
        @Cleanup
        Throttler t = new AutoCompleteTestThrottler(CONTAINER_ID, wrap(calculator), executorService(), metrics, delays::add);

        // Set a non-maximum delay and ask to throttle, then verify we throttled the correct amount.
        calculator.setDelayMillis(NON_MAX_THROTTLE_MILLIS);
        t.throttle().get(SHORT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals("Expected exactly one delay to be recorded.", 1, delays.size());
        Assert.assertEquals("Unexpected delay recorded.", NON_MAX_THROTTLE_MILLIS, (int) delays.get(0));
    }

    /**
     * Tests the case when {@link ThrottlerCalculator#getThrottlingDelay()} returns a value which requires repeated
     * delays (Maximum Delay == True).
     */
    @Test
    public void testMaximumDelay() throws Exception {
        final int repeatCount = 3;
        val delays = Collections.<Integer>synchronizedList(new ArrayList<>());
        val calculator = new TestCalculatorThrottler(THROTTLER_NAME);

        val nextDelay = new AtomicInteger(MAX_THROTTLE_MILLIS + repeatCount - 1);
        Consumer<Integer> recordDelay = delayMillis -> {
            delays.add(delayMillis);
            calculator.setDelayMillis(nextDelay.decrementAndGet());
        };

        // Request a throttling delay. Since we begin with a value higher than the MAX, we expect the throttler to
        // block as long as necessary; at each throttle cycle it should check the calculator for a new value, which we
        // will decrease an expect to unblock once we got a value smaller than MAX.
        @Cleanup
        Throttler t = new AutoCompleteTestThrottler(CONTAINER_ID, wrap(calculator), executorService(), metrics, recordDelay);
        calculator.setDelayMillis(nextDelay.get());
        t.throttle().get(SHORT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        Assert.assertEquals("Unexpected number of delays recorded.", repeatCount, delays.size());
        for (int i = 0; i < repeatCount; i++) {
            int expectedDelay = MAX_THROTTLE_MILLIS + Math.min(0, repeatCount - i); // Delays are capped.
            Assert.assertEquals("Unexpected delay recorded for step " + i, expectedDelay, (int) delays.get(i));
        }
    }

    /**
     * Tests interruptible Cache delays.
     */
    @Test
    public void testInterruptedCacheDelay() throws Exception {
        testInterruptedDelay(ThrottlerCalculator.ThrottlerName.Cache);
    }

    /**
     * Tests interruptible DurableDataLog delays.
     *
     * @throws Exception
     */
    @Test
    public void testInterruptedDurableDataLogDelay() throws Exception {
        testInterruptedDelay(ThrottlerCalculator.ThrottlerName.DurableDataLog);
    }

    /**
     * Tests the case when {@link Throttler#throttle()} returns a delay that can be interrupted using {@link Throttler#notifyThrottleSourceChanged()}}.
     */
    private void testInterruptedDelay(ThrottlerCalculator.ThrottlerName throttlerName) throws Exception {
        val suppliedDelays = Arrays.asList(5000, 2500, 5000);
        val delays = Collections.<Integer>synchronizedList(new ArrayList<>());
        val calculator = new TestCalculatorThrottler(throttlerName);
        val nextDelay = suppliedDelays.iterator();
        Consumer<Integer> recordDelay = delayMillis -> {
            delays.add(delayMillis);
            calculator.setDelayMillis(nextDelay.hasNext() ? nextDelay.next() : 0); // 0 means we're done (no more throttling).
        };
        @Cleanup
        TestThrottler t = new TestThrottler(CONTAINER_ID, wrap(calculator), executorService(), metrics, recordDelay);

        // Set a non-maximum delay and ask to throttle, then verify we throttled the correct amount.
        calculator.setDelayMillis(nextDelay.next());
        val t1 = t.throttle();
        Assert.assertFalse("Not expected throttle future to be completed yet.", t1.isDone());

        // For every delay that we want to submit, notify that the cache cleanup has completed, which should cancel the
        // currently running throttle cycle and request the next throttling value.
        for (int i = 1; i < suppliedDelays.size(); i++) {
            // Interrupt the current throttle cycle.
            t.notifyThrottleSourceChanged();
            Assert.assertFalse("Not expected throttle future to be completed yet.", t1.isDone());

            // Wait for the new cycle to begin (we use the recordDelay consumer above to figure this out).
            int expectedDelayCount = i + 1;
            TestUtils.await(() -> delays.size() == expectedDelayCount, 5, TIMEOUT_MILLIS);
        }

        // When we are done, complete the last throttle cycle and check final results.
        t.completeDelayFuture();
        TestUtils.await(t1::isDone, 5, TIMEOUT_MILLIS);
        Assert.assertEquals("Unexpected number of delays recorded.", suppliedDelays.size(), delays.size());

        Assert.assertEquals("Unexpected first delay value.", suppliedDelays.get(0), delays.get(0));

        // Subsequent delays cannot be predicted due to them being real time values and, as such, vary greatly between
        // runs and different environments. We can only check that they decrease in value (by design).
        for (int i = 1; i < delays.size(); i++) {
            AssertExtensions.assertLessThanOrEqual("Expected delays to be decreasing.", delays.get(i - 1), delays.get(i));
        }
    }

    private ThrottlerCalculator wrap(ThrottlerCalculator.Throttler calculatorThrottler) {
        return ThrottlerCalculator.builder().throttler(calculatorThrottler).build();
    }

    //region Helper Classes

    @RequiredArgsConstructor
    @Getter
    @Setter
    private static class TestCalculatorThrottler extends ThrottlerCalculator.Throttler {
        private final ThrottlerCalculator.ThrottlerName name;
        private boolean throttlingRequired;
        private int delayMillis;
    }

    private static class TestThrottler extends Throttler {
        private final Consumer<Integer> newDelay;
        private final AtomicReference<CompletableFuture<Void>> lastDelayFuture = new AtomicReference<>();

        TestThrottler(int containerId, ThrottlerCalculator calculator, ScheduledExecutorService executor,
                      SegmentStoreMetrics.OperationProcessor metrics, Consumer<Integer> newDelay) {
            super(containerId, calculator, executor, metrics);
            this.newDelay = newDelay;
        }

        @Override
        protected CompletableFuture<Void> createDelayFuture(int millis) {
            this.newDelay.accept(millis);
            val oldDelay = this.lastDelayFuture.getAndSet(null);
            Assert.assertTrue(oldDelay == null || oldDelay.isDone());
            val result = super.createDelayFuture(millis);
            this.lastDelayFuture.set(result);
            return result;
        }

        void completeDelayFuture() {
            val delayFuture = this.lastDelayFuture.getAndSet(null);
            Assert.assertNotNull(delayFuture);
            delayFuture.complete(null);
        }
    }

    /**
     * Overrides the {@link Throttler#createDelayFuture(int)} method to return a completed future and record the delay
     * requested. This is necessary to avoid having to wait indeterminate amounts of time to actually verify the throttling
     * mechanism.
     */
    private static class AutoCompleteTestThrottler extends TestThrottler {
        AutoCompleteTestThrottler(int containerId, ThrottlerCalculator calculator, ScheduledExecutorService executor,
                                  SegmentStoreMetrics.OperationProcessor metrics, Consumer<Integer> newDelay) {
            super(containerId, calculator, executor, metrics, newDelay);
        }

        @Override
        protected CompletableFuture<Void> createDelayFuture(int millis) {
            val delayFuture = super.createDelayFuture(millis);
            super.completeDelayFuture();
            return delayFuture;
        }
    }

    //endregion
}

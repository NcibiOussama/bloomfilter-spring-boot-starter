package io.github.ncibioussama.demo;

import java.time.Duration;
import java.util.function.BooleanSupplier;

final class Await {

    private Await() {
    }

    static void until(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for: " + description);
            }
        }
        throw new AssertionError("timed out after " + timeout + " waiting for: " + description);
    }
}

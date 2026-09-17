package dev.ben.minijvm.runtime;

import java.util.Objects;
import java.util.Set;

/**
 * Deterministic outcome metrics for a single guest garbage collection cycle (ADR-006, ADR-014).
 *
 * @param rootsCount       Total unique GC roots identified across stack frames and static fields.
 * @param markedCount      Total guest objects/arrays reachable and marked during traversal.
 * @param reclaimedCount   Total unreachable guest objects/arrays swept and deallocated.
 * @param survivorsCount   Total live guest objects/arrays remaining resident in the Heap.
 * @param reclaimedHandles Unmodifiable set of reclaimed guest handles.
 * @param survivingHandles Unmodifiable set of surviving live guest handles.
 */
public record GcResult(
        int rootsCount,
        int markedCount,
        int reclaimedCount,
        int survivorsCount,
        Set<Long> reclaimedHandles,
        Set<Long> survivingHandles
) {
    public GcResult {
        Objects.requireNonNull(reclaimedHandles, "reclaimedHandles cannot be null");
        Objects.requireNonNull(survivingHandles, "survivingHandles cannot be null");
        reclaimedHandles = Set.copyOf(reclaimedHandles);
        survivingHandles = Set.copyOf(survivingHandles);
    }

    public boolean hasReclaimed() {
        return reclaimedCount > 0;
    }

    @Override
    public String toString() {
        return String.format(
                "GcResult[roots=%d, marked=%d, reclaimed=%d, survivors=%d]",
                rootsCount, markedCount, reclaimedCount, survivorsCount
        );
    }
}

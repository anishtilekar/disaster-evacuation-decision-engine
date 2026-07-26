package com.evacuation.engine.loader;

import com.evacuation.engine.graph.structure.GraphSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current immutable {@link GraphSnapshot} behind an {@link AtomicReference}, giving the
 * router lock-free copy-on-write access.
 *
 * <p>Each snapshot is fully immutable, so a routing query simply grabs the current reference once and
 * reads it with no locking; concurrent readers never block each other. A rebuild produces an entirely
 * new snapshot and publishes it with a single atomic {@code set}, so an in-flight query keeps reading
 * the snapshot it already holds while new queries pick up the replacement — the swap can neither block
 * nor corrupt a read. This is the concurrency approach the architecture doc calls out: no shared
 * mutable graph state, just atomic replacement of an immutable value.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GraphCache {

    private final GraphBuilder graphBuilder;

    private final AtomicReference<GraphSnapshot> current = new AtomicReference<>();

    /**
     * Returns the current snapshot.
     *
     * @return the current graph snapshot
     * @throws IllegalStateException if no snapshot has been loaded yet — i.e. {@link #reload()} has
     *                               not run (such as before the startup seeder executes)
     */
    public GraphSnapshot get() {
        GraphSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("Graph snapshot not yet loaded — call reload() first");
        }
        return snapshot;
    }

    /**
     * Rebuilds the graph from the database and atomically publishes the new snapshot as current.
     *
     * @return the newly built snapshot
     */
    public GraphSnapshot reload() {
        GraphSnapshot snapshot = graphBuilder.build();
        current.set(snapshot);
        log.info("Graph snapshot reloaded: version={}, {} nodes, {} edge slots, {} shelters",
                snapshot.graphVersion(), snapshot.nodeCount(), snapshot.edgeSlotCount(),
                snapshot.shelters().size());
        return snapshot;
    }

    /**
     * @return {@code true} if a snapshot has been loaded, so callers (a health check, the startup
     *         seeder) can check without triggering the {@link #get()} not-loaded exception
     */
    public boolean isLoaded() {
        return current.get() != null;
    }
}
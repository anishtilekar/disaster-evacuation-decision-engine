package com.evacuation.engine.loader;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.model.enums.NodeType;
import com.evacuation.engine.model.enums.RoadStatus;
import com.evacuation.engine.model.enums.ShelterStatus;
import com.evacuation.engine.osm.OsmImportService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the readiness verdict {@link GraphStartupRunner} ends with, and the fail-fast lever that
 * verdict gates.
 *
 * <p>This exists because of a real incident rather than for symmetry with the other startup tests.
 * An OSM import aborted on a schema mismatch, the {@code ERROR} scrolled past inside tens of
 * thousands of lines of Hibernate SQL, and Spring Boot printed its usual "Started ..." line — so the
 * application reported a clean boot while holding a zero-node snapshot that could not route a single
 * party. The interesting property is therefore not "does it log something" but <em>which state it
 * classifies as unroutable</em>, since the two states that caused the most confusion (an import that
 * finishes with no shelters, or with no edges) throw nothing at all upstream and would otherwise be
 * indistinguishable from success.
 *
 * <p>The default no-throw contract is asserted alongside each verdict, because it is load-bearing in
 * the opposite direction: a local or demo boot must still come up so an operator can retry the
 * import, and a well-meaning change that made an unroutable graph fatal by default would break that
 * deliberately. Each case is asserted both ways — silent by default, fatal only when asked.
 */
class GraphStartupRunnerTest {

    private static final long GRAPH_VERSION = 7L;

    /**
     * A snapshot with exactly the pieces each case wants to withhold. One node is always present
     * (the zero-node case builds its own), so "no edges" and "no shelters" are tested in isolation
     * rather than tangled together with an empty graph.
     */
    private static GraphSnapshot snapshot(boolean withEdges, boolean withShelters) {
        int nodeCount = 2;
        long[] dbNodeId = {1L, 2L};
        String[] nodeName = {"A", "B"};
        double[] nodeLat = {18.50, 18.51};
        double[] nodeLon = {73.85, 73.86};
        NodeType[] nodeType = {NodeType.INTERSECTION, NodeType.INTERSECTION};
        boolean[] nodeActive = {true, true};
        double[] nodeCapacity = {5_000.0, 5_000.0};
        Map<Long, Integer> nodeIdToIndex = Map.of(1L, 0, 2L, 1);

        int slotCount = withEdges ? 1 : 0;
        // CSR: node 0 owns [0, slotCount), node 1 owns nothing.
        int[] edgeHead = {0, slotCount, slotCount};
        int[] edgeTo = withEdges ? new int[]{1} : new int[0];
        long[] edgeDbId = withEdges ? new long[]{1L} : new long[0];
        double[] edgeDistanceKm = withEdges ? new double[]{1.0} : new double[0];
        double[] edgeTimeMin = withEdges ? new double[]{1.0} : new double[0];
        double[] edgeCapacity = withEdges ? new double[]{5_000.0} : new double[0];
        RoadStatus[] edgeBaseStatus = withEdges ? new RoadStatus[]{RoadStatus.OPEN} : new RoadStatus[0];

        List<GraphSnapshot.ShelterRef> shelters = withShelters
                ? List.of(new GraphSnapshot.ShelterRef(1L, 1, "Shelter", ShelterStatus.AVAILABLE,
                        100, false, 18.51, 73.86))
                : List.of();

        return new GraphSnapshot(dbNodeId, nodeName, nodeLat, nodeLon, nodeType, nodeActive,
                nodeCapacity, nodeIdToIndex, edgeHead, edgeTo, edgeDbId, edgeDistanceKm, edgeTimeMin,
                edgeCapacity, edgeBaseStatus, shelters, GRAPH_VERSION, LocalDateTime.now());
    }

    /** The zero-node snapshot: a real object, so the runner reads 0 rather than hitting a null. */
    private static GraphSnapshot emptySnapshot() {
        return new GraphSnapshot(new long[0], new String[0], new double[0], new double[0],
                new NodeType[0], new boolean[0], new double[0], Map.of(), new int[]{0}, new int[0],
                new long[0], new double[0], new double[0], new double[0], new RoadStatus[0],
                List.of(), GRAPH_VERSION, LocalDateTime.now());
    }

    /**
     * A runner wired to a cache in whatever state the case needs. Seeding is switched off so no case
     * depends on an import running; the import-failure path is exercised separately below.
     */
    private static GraphStartupRunner runnerFor(GraphCache graphCache, boolean failOnUnroutable) {
        OsmImportService importService = mock(OsmImportService.class);
        when(importService.isGraphEmpty()).thenReturn(false);

        GraphEngineProperties properties = new GraphEngineProperties();
        properties.setSeedOnStartup(false);
        properties.setFailOnUnroutableGraph(failOnUnroutable);

        return new GraphStartupRunner(importService, graphCache, properties);
    }

    private static GraphCache cacheHolding(GraphSnapshot snapshot) {
        GraphCache cache = mock(GraphCache.class);
        when(cache.isLoaded()).thenReturn(snapshot != null);
        if (snapshot != null) {
            when(cache.get()).thenReturn(snapshot);
        } else {
            when(cache.get()).thenThrow(new IllegalStateException("Graph snapshot not yet loaded"));
        }
        return cache;
    }

    private static void run(GraphStartupRunner runner) throws Exception {
        runner.run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName("A graph with nodes, edges and a shelter starts cleanly under either setting")
    void routableGraphStartsCleanly() {
        GraphCache cache = cacheHolding(snapshot(true, true));

        assertDoesNotThrow(() -> run(runnerFor(cache, false)));
        // Fail-fast must not fire on a healthy graph — that is the whole point of gating on the
        // verdict rather than on "did anything go wrong anywhere during startup".
        assertDoesNotThrow(() -> run(runnerFor(cache, true)));
    }

    @Test
    @DisplayName("No snapshot at all: silent by default, fatal only when fail-on-unroutable is set")
    void missingSnapshotIsUnroutable() {
        GraphCache cache = cacheHolding(null);

        assertDoesNotThrow(() -> run(runnerFor(cache, false)),
                "the documented no-crash-loop default must survive: an operator has to be able to "
                        + "boot and retry the import");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run(runnerFor(cache, true)));
        assertTrue(thrown.getMessage().contains("no graph snapshot"), thrown.getMessage());
    }

    @Test
    @DisplayName("A zero-node snapshot is unroutable even though nothing threw")
    void emptySnapshotIsUnroutable() {
        // This is the exact shape of the real incident: reload() succeeded, so no exception was
        // raised anywhere, and the app reported a healthy start with 0 nodes.
        GraphCache cache = cacheHolding(emptySnapshot());

        assertDoesNotThrow(() -> run(runnerFor(cache, false)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run(runnerFor(cache, true)));
        assertTrue(thrown.getMessage().contains("0 nodes"), thrown.getMessage());
    }

    @Test
    @DisplayName("A graph with no shelters is degraded: every search would have no target")
    void graphWithoutSheltersIsDegraded() {
        GraphCache cache = cacheHolding(snapshot(true, false));

        assertDoesNotThrow(() -> run(runnerFor(cache, false)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run(runnerFor(cache, true)));
        assertTrue(thrown.getMessage().contains("no shelters"), thrown.getMessage());
    }

    @Test
    @DisplayName("A graph with no edges is degraded: nobody can move")
    void graphWithoutEdgesIsDegraded() {
        GraphCache cache = cacheHolding(snapshot(false, true));

        assertDoesNotThrow(() -> run(runnerFor(cache, false)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run(runnerFor(cache, true)));
        assertTrue(thrown.getMessage().contains("no edges"), thrown.getMessage());
    }

    @Test
    @DisplayName("A failed import still reaches the verdict rather than aborting the runner")
    void failedImportStillProducesAVerdict() throws Exception {
        // The import throwing must not short-circuit step 4 — otherwise the one situation the
        // verdict was added for is the one situation that never prints it.
        OsmImportService importService = mock(OsmImportService.class);
        when(importService.isGraphEmpty()).thenReturn(true);
        when(importService.importWard()).thenThrow(new RuntimeException("Overpass unreachable"));

        GraphCache cache = cacheHolding(emptySnapshot());

        GraphEngineProperties lenient = new GraphEngineProperties();
        lenient.setSeedOnStartup(true);
        assertDoesNotThrow(() ->
                new GraphStartupRunner(importService, cache, lenient)
                        .run(new DefaultApplicationArguments()));

        GraphEngineProperties strict = new GraphEngineProperties();
        strict.setSeedOnStartup(true);
        strict.setFailOnUnroutableGraph(true);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                new GraphStartupRunner(importService, cache, strict)
                        .run(new DefaultApplicationArguments()));
        assertTrue(thrown.getMessage().contains("0 nodes"), thrown.getMessage());
    }

    @Test
    @DisplayName("A cache whose reload() throws is reported, not propagated")
    void reloadFailureIsCaught() {
        // reload() blowing up is the other route to "no snapshot", and it must be handled the same
        // way as a snapshot that never existed rather than escaping as whatever the cache threw.
        GraphCache cache = mock(GraphCache.class);
        when(cache.reload()).thenThrow(new RuntimeException("database unreachable"));
        when(cache.isLoaded()).thenReturn(false);
        when(cache.get()).thenThrow(new IllegalStateException("Graph snapshot not yet loaded"));

        assertDoesNotThrow(() -> run(runnerFor(cache, false)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run(runnerFor(cache, true)));
        assertTrue(thrown.getMessage().contains("no graph snapshot"), thrown.getMessage());
    }
}

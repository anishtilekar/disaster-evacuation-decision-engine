package com.evacuation.engine.loader;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.osm.OsmImportService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Application-startup entry point that ties the OSM import pipeline to the in-memory graph cache.
 *
 * <p>On boot it optionally seeds the database (import the configured ward from OSM or its cache when
 * {@code graph.seed-on-startup} is on and {@code road_nodes} is empty), then builds the
 * {@link GraphCache} so a snapshot is ready before the app serves routing requests. All real work
 * lives in {@link OsmImportService} and {@code GraphBuilder}/{@link GraphCache}; this class is just a
 * thin orchestrator.
 *
 * <p>Failures here are logged, never rethrown by default. An unconfigured ward, a missing ward
 * polygon, or an unreachable Overpass server should not crash-loop the whole application: the app
 * boots with no (or stale) graph, and an admin can retry the import later via the graph endpoints.
 * Any caller that then needs a snapshot before one exists gets a clear {@link IllegalStateException}
 * from {@link GraphCache#get()} — an acceptable, localized failure rather than a boot-time outage.
 *
 * <p><strong>But "don't crash" must not mean "don't say anything".</strong> The original version of
 * this class logged its failures and stopped there, which turned out to hide a real outage in
 * practice: an import aborted on a schema mismatch, the {@code ERROR} scrolled past inside tens of
 * thousands of lines of Hibernate DDL/SQL, and Spring Boot then printed its ordinary
 * {@code Started EvacuationEngineApplication} line. The application looked healthy and was in fact
 * incapable of routing a single party — every dispatch call would have failed on an empty snapshot.
 * A logged error that nobody can find is indistinguishable from no error at all.
 *
 * <p>So this class now ends with an explicit readiness verdict — {@link #logReadiness} — evaluated
 * against the snapshot that actually got built rather than against whether any step threw. That
 * distinction matters: an import can "succeed" and still leave a graph that cannot route (zero
 * shelters means every shelter search has no target; zero edges means nobody can move), and those
 * states deserve to be named as loudly as an outright failure. The verdict is printed as a boxed
 * banner precisely so it survives a noisy log.
 *
 * <p>For deployments that would rather not come up at all than come up unable to route,
 * {@code graph.fail-on-unroutable-graph=true} turns the NOT-READY verdict into a startup failure.
 * It defaults to {@code false}, preserving the documented no-crash-loop behaviour for local and
 * demo use, and is offered as configuration rather than a hardcoded choice for the same reason the
 * rest of {@link GraphEngineProperties} is — this is an operator's call, not the code's.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GraphStartupRunner implements ApplicationRunner {

    private final OsmImportService osmImportService;
    private final GraphCache graphCache;
    private final GraphEngineProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1-2. Seed from OSM only when enabled and the graph is empty; otherwise log why we skip.
        boolean importFailed = false;
        if (!properties.isSeedOnStartup()) {
            log.info("Seed-on-startup disabled; skipping OSM import.");
        } else if (!osmImportService.isGraphEmpty()) {
            log.info("Graph already populated (road_nodes non-empty); skipping OSM import.");
        } else {
            log.info("Road network is empty; starting OSM import for the configured ward.");
            try {
                OsmImportService.ImportSummary summary = osmImportService.importWard();
                log.info("OSM import complete for ward '{}': {} nodes, {} edges, {} shelters.",
                        summary.wardName(), summary.nodes(), summary.edges(), summary.shelters());
            } catch (Exception ex) {
                importFailed = true;
                log.error("OSM import failed; startup will continue with an empty/stale graph. "
                        + "Retry the import later via the graph import endpoint.", ex);
            }
        }

        // 3. Always (re)build the cache so a snapshot is ready if the data allows it.
        try {
            graphCache.reload();
        } catch (Exception ex) {
            log.error("Graph cache could not be built (the database may still be empty after a failed "
                    + "import). Routing requests will fail until a snapshot is loaded via a later reload.", ex);
        }

        // 4. State plainly whether this instance can actually route, however step 3 went.
        logReadiness(importFailed);
    }

    /**
     * Prints the boxed readiness verdict, and fails startup if the operator asked for that.
     *
     * <p>Judged on the built snapshot's own contents, not on whether the earlier steps threw. Three
     * outcomes are possible, and only the first is a working engine:
     *
     * <ul>
     *   <li><strong>READY</strong> — nodes, edges, and at least one shelter all present.</li>
     *   <li><strong>DEGRADED</strong> — a snapshot exists but is missing something routing cannot do
     *       without. Called out separately because nothing upstream reports it as an error: the
     *       import can finish "successfully" having found no shelters in the ward at all, and every
     *       subsequent search would then fail to place anybody with no obvious cause.</li>
     *   <li><strong>NOT READY</strong> — no snapshot, or a snapshot with no nodes.</li>
     * </ul>
     *
     * @param importFailed whether the OSM import step threw, so the banner can point at the most
     *                     likely cause rather than making an operator correlate two log lines
     */
    private void logReadiness(boolean importFailed) {
        if (!graphCache.isLoaded()) {
            banner("NOT READY - no graph snapshot was built. This instance cannot route.",
                    importFailed
                            ? "Cause: the OSM import failed above; see the stack trace for details."
                            : "Cause: the road network tables are empty and seeding did not populate them.",
                    "Every dispatch, repair, and graph request will fail until a snapshot is loaded.");
            failIfConfigured("no graph snapshot was built");
            return;
        }

        GraphSnapshot snapshot = graphCache.get();
        if (snapshot.nodeCount() == 0) {
            banner("NOT READY - the graph snapshot is empty (0 nodes). This instance cannot route.",
                    importFailed
                            ? "Cause: the OSM import failed above; see the stack trace for details."
                            : "Cause: the road network tables contain no nodes.",
                    "Every dispatch, repair, and graph request will fail until real data is imported.");
            failIfConfigured("the graph snapshot has 0 nodes");
            return;
        }

        // A snapshot that exists but cannot get anyone anywhere. Neither condition throws upstream,
        // which is exactly why both are surfaced here.
        boolean noEdges = snapshot.edgeSlotCount() == 0;
        boolean noShelters = snapshot.shelters().isEmpty();
        if (noEdges || noShelters) {
            String missing = noEdges && noShelters ? "no edges and no shelters"
                    : noEdges ? "no edges" : "no shelters";
            banner("DEGRADED - the graph has " + missing + ". Routing will place nobody.",
                    String.format("Snapshot v%d: %d nodes, %d edge slots, %d shelters.",
                            snapshot.graphVersion(), snapshot.nodeCount(),
                            snapshot.edgeSlotCount(), snapshot.shelters().size()),
                    noShelters
                            ? "Every shelter search has no target, so every party is a shortfall."
                            : "No party can move, so every party is a shortfall.");
            failIfConfigured("the graph has " + missing);
            return;
        }

        log.info("Graph READY: snapshot v{} - {} nodes, {} edge slots, {} shelters. "
                        + "Dispatch, repair, and graph endpoints are live.",
                snapshot.graphVersion(), snapshot.nodeCount(), snapshot.edgeSlotCount(),
                snapshot.shelters().size());
    }

    /**
     * Rethrows as a startup failure when {@code graph.fail-on-unroutable-graph} is set, so a
     * deployment that must never serve an unroutable engine stops here instead of coming up broken.
     *
     * @param reason the verdict's own summary, carried into the exception message
     * @throws IllegalStateException if the operator opted into fail-fast
     */
    private void failIfConfigured(String reason) {
        if (properties.isFailOnUnroutableGraph()) {
            throw new IllegalStateException(
                    "Refusing to start with an unroutable graph: " + reason
                            + " (graph.fail-on-unroutable-graph=true)");
        }
    }

    /**
     * Logs a boxed, hard-to-miss multi-line warning.
     *
     * <p>A box rather than a plain {@code log.warn} because the message this exists to deliver has
     * already been lost once in a wall of Hibernate SQL. The banner is emitted as a single log event
     * so nothing can interleave into the middle of it.
     */
    private void banner(String... lines) {
        StringBuilder sb = new StringBuilder(System.lineSeparator());
        String rule = "=".repeat(100);
        sb.append(rule).append(System.lineSeparator());
        sb.append("  GRAPH ENGINE STARTUP CHECK").append(System.lineSeparator());
        for (String line : lines) {
            sb.append("  ").append(line).append(System.lineSeparator());
        }
        sb.append(rule);
        log.warn(sb.toString());
    }
}

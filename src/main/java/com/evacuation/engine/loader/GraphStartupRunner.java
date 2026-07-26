package com.evacuation.engine.loader;

import com.evacuation.engine.config.GraphEngineProperties;
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
 * <p>Failures here are logged, never rethrown. An unconfigured ward, a missing ward polygon, or an
 * unreachable Overpass server should not crash-loop the whole application: the app boots with no (or
 * stale) graph, and an admin can retry the import later via the graph endpoints. Any caller that then
 * needs a snapshot before one exists gets a clear {@link IllegalStateException} from
 * {@link GraphCache#get()} — an acceptable, localized failure rather than a boot-time outage.
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
    }
}
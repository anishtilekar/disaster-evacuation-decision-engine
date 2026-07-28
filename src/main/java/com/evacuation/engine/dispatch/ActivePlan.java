package com.evacuation.engine.dispatch;

import com.evacuation.engine.config.GraphEngineProperties;
import com.evacuation.engine.graph.structure.GraphSnapshot;
import com.evacuation.engine.graph.time.TimeModel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * The retained mutable state of the current operational dispatch session: exactly one
 * {@link ReservationLedger} and one {@link PlanBook}, shared by the service that reserves into them
 * ({@code DispatchService}) and the service that later repairs against them ({@code RepairService}).
 *
 * <p><strong>Why this exists — a real change in how dispatch retains state.</strong> Dispatch used
 * to build a brand-new ledger inside every {@code plan(...)} call, which meant every call reasoned
 * against an empty board: nothing a previous pass had reserved was visible to the next one. That is
 * acceptable for a single one-shot plan and useless for anything that has to react to the plan
 * already in force. Here the ledger and the plan book instead <em>persist across calls</em> — and
 * across repair passes — until something explicitly
 * {@link #reset(GraphSnapshot, TimeModel, GraphEngineProperties) resets} them. That persistence is
 * the whole point: it is what lets a later repair operation find the reservations an earlier
 * dispatch pass made and modify them, rather than re-planning from a blank slate every time.
 *
 * <p><strong>Lifecycle — the same discipline as {@code GraphCache} and
 * {@code HazardTimelineCache}.</strong> A {@link ReservationLedger} must be sized against a
 * {@link GraphSnapshot}, and Spring builds its singletons before the startup runner has imported the
 * graph — so, exactly like those two caches, this bean constructs nothing graph-dependent in its
 * constructor. It holds a {@code null} ledger until an explicit {@link #reset} call populates it,
 * mirroring {@code reload(...)} in those classes. That deferral is precisely what makes it safe to
 * register as a true Spring singleton despite the ledger's dependence on a snapshot that does not yet
 * exist at context-startup time.
 *
 * <p>The plan book is different: it needs no snapshot to exist, so it starts empty rather than
 * unbuilt and is created eagerly. {@link #reset} clears it in place rather than replacing it, and
 * {@link #planBook()} can hand it out unconditionally — there is no unbuilt state to guard against.
 *
 * <p><strong>The session epoch is fixed at {@link #reset} and never silently moves.</strong> Bucket
 * 0 in every {@code HazardTimeline} means "the epoch it was compiled against" — fine for one compile,
 * wrong across several, since two compiles made minutes apart would otherwise each plant bucket 0 at
 * a different real moment and silently misalign any reservation made against the first. A session
 * that dispatches once and repairs later needs bucket 0 to mean the same real instant every time it
 * asks "what bucket is now", so {@link #reset} records that instant once and {@link #sessionEpoch()}
 * hands out that same value for the rest of the session — including to every repair-triggered
 * recompile, which must pass it to {@code HazardTimelineCache.reload(GraphSnapshot, LocalDateTime)}
 * rather than letting that call default to its own {@code LocalDateTime.now()}.
 */
@Component
@Slf4j
public class ActivePlan {

    /**
     * The current session's reservation board. Null until
     * {@link #reset(GraphSnapshot, TimeModel, GraphEngineProperties)} sizes one against a snapshot;
     * a Spring singleton cannot build this in its constructor because the graph does not yet exist
     * when the bean is created.
     */
    private ReservationLedger ledger;

    /**
     * The current session's plan book. Unlike the ledger it needs no snapshot to exist, so it starts
     * empty rather than unbuilt and is safe to construct eagerly; {@link #reset} clears it rather
     * than replacing the instance.
     */
    private final PlanBook planBook = new PlanBook();

    /**
     * The instant bucket 0 has meant since {@link #reset}, fixed for the whole session. Null until
     * the first {@link #reset} call, exactly like the ledger.
     */
    private LocalDateTime sessionEpoch;

    /**
     * Begins a fresh operational session: sizes a new {@link ReservationLedger} against
     * {@code snapshot} (using {@code timeModel} for the horizon and {@code properties} for the
     * capacity headroom), publishes it as the current ledger, clears the plan book back to empty,
     * and fixes {@code epoch} as this session's bucket-0 instant for every compile from here on. Any
     * reservations and plans from a prior session are dropped wholesale — this is the one operation
     * that starts the board over, and every reserve or repair after it works against this new,
     * initially-empty state.
     *
     * @param snapshot   the immutable graph the new ledger is sized against
     * @param timeModel  the time discretisation whose horizon the ledger spans
     * @param properties engine configuration, including the capacity headroom the ledger applies
     * @param epoch      the instant this session's bucket 0 represents, fixed until the next reset
     */
    public synchronized void reset(GraphSnapshot snapshot, TimeModel timeModel,
                                   GraphEngineProperties properties, LocalDateTime epoch) {
        this.ledger = new ReservationLedger(snapshot, timeModel, properties);
        this.sessionEpoch = epoch;
        planBook.clear();
        log.info("New operational dispatch session started against graph v{}, epoch {}",
                snapshot.graphVersion(), epoch);
    }

    /**
     * Returns the current session's reservation ledger for reading or reserving against.
     *
     * @return the ledger built by the most recent {@link #reset}
     * @throws IllegalStateException if no session has been started yet — a ledger cannot exist before
     *                               a snapshot has been handed to {@link #reset}
     */
    public synchronized ReservationLedger ledger() {
        if (ledger == null) {
            throw new IllegalStateException("No active dispatch session — call reset(...) first");
        }
        return ledger;
    }

    /**
     * Returns the current session's plan book directly. Unlike {@link #ledger()} this never throws:
     * the plan book is always non-null because it starts empty rather than needing a snapshot to be
     * built.
     *
     * @return the current plan book, always non-null
     */
    public PlanBook planBook() {
        return planBook;
    }

    /**
     * Returns the instant this session's bucket 0 represents — the epoch every timeline compile in
     * this session, including every repair-triggered recompile, must be anchored to.
     *
     * @return the epoch fixed by the most recent {@link #reset}
     * @throws IllegalStateException if no session has been started yet
     */
    public synchronized LocalDateTime sessionEpoch() {
        if (sessionEpoch == null) {
            throw new IllegalStateException("No active dispatch session — call reset(...) first");
        }
        return sessionEpoch;
    }

    /**
     * Whether a session is currently live — i.e. {@link #reset} has run and a ledger exists. Lets a
     * caller decide whether to act instead of calling {@link #ledger()} and catching its exception.
     *
     * @return {@code true} if a ledger has been built by {@link #reset}, {@code false} otherwise
     */
    public synchronized boolean isActive() {
        return ledger != null;
    }
}
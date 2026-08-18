package net.quest_items.locator;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooperative, tick-budgeted worker scheduler, following the scheme used by Explorer's Compass:
 * long-running searches run on the server thread, but only in the time left over at the end of
 * each tick (aiming to keep the total tick near 50ms, with a guaranteed minimum slice of 10ms),
 * so the server never freezes for the whole duration of a search.
 *
 * tickStart() must be called at the head of every server tick, tickEnd() at its tail.
 */
public final class TickWorkers {

    public interface Worker {
        /** Whether this worker still has more units of work to do. */
        boolean hasWork();

        /**
         * Perform one unit of work. Returns true if this worker may immediately be given
         * another unit within the same tick's budget, false to yield to the next worker.
         */
        boolean doWork();
    }

    private static final List<Worker> workers = new ArrayList<>();
    private static long startTime = -1;
    private static int index = 0;

    private TickWorkers() {}

    public static void tickStart() {
        startTime = System.currentTimeMillis();
    }

    public static void tickEnd() {
        index = 0;
        Worker task = getNext();
        if (task == null) {
            return;
        }

        long time = 50 - (System.currentTimeMillis() - startTime);
        if (time < 10) {
            time = 10;
        }
        time += System.currentTimeMillis();

        while (System.currentTimeMillis() < time && task != null) {
            boolean again = task.doWork();

            if (!task.hasWork()) {
                remove(task);
                task = getNext();
            } else if (!again) {
                task = getNext();
            }
        }
    }

    public static synchronized void add(Worker worker) {
        workers.add(worker);
    }

    private static synchronized Worker getNext() {
        return workers.size() > index ? workers.get(index++) : null;
    }

    private static synchronized void remove(Worker worker) {
        workers.remove(worker);
        index--;
    }

    public static synchronized void clear() {
        workers.clear();
    }
}

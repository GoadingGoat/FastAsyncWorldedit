package com.boydti.fawe.util;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.FaweQueue;
import com.sk89q.worldedit.world.World;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public class SetQueue {

    /**
     * The implementation specific queue
     */
    public static final SetQueue IMP = new SetQueue();

    public enum QueueStage {
        INACTIVE, ACTIVE, NONE;
    }

    private final ConcurrentLinkedDeque<FaweQueue> activeQueues;
    private final ConcurrentLinkedDeque<FaweQueue> inactiveQueues;
    private final ConcurrentLinkedDeque<Runnable> tasks;

    /**
     * Used to calculate elapsed time in milliseconds and ensure block placement doesn't lag the server
     */
    private long last;
    private long secondLast;
    private long lastSuccess;

    /**
     * A queue of tasks that will run when the queue is empty
     */
    private final ConcurrentLinkedDeque<Runnable> emptyTasks = new ConcurrentLinkedDeque<>();

    private ForkJoinPool pool = new ForkJoinPool();
    private ExecutorCompletionService completer = new ExecutorCompletionService(pool);

    /**
     * @see TaskManager#getPublicForkJoinPool()
     * @return ForkJoinPool
     */
    @Deprecated
    public ExecutorCompletionService getCompleterService() {
        return completer;
    }

    public SetQueue() {
        tasks = new ConcurrentLinkedDeque<>();
        activeQueues = new ConcurrentLinkedDeque();
        inactiveQueues = new ConcurrentLinkedDeque<>();
        TaskManager.IMP.repeat(new Runnable() {
            @Override
            public void run() {
                try {
                    double targetTPS = 18 - Math.max(Settings.QUEUE.EXTRA_TIME_MS * 0.05, 0);
                    do {
                        Runnable task = tasks.poll();
                        if (task != null) {
                            task.run();
                        } else {
                            break;
                        }
                    } while (Fawe.get().getTimer().isAbove(targetTPS));
                    if (inactiveQueues.isEmpty() && activeQueues.isEmpty()) {
                        lastSuccess = System.currentTimeMillis();
                        runEmptyTasks();
                        return;
                    }
                    if (!MemUtil.isMemoryFree()) {
                        final int mem = MemUtil.calculateMemory();
                        if (mem != Integer.MAX_VALUE) {
                            if ((mem <= 1) && Settings.PREVENT_CRASHES) {
                                for (FaweQueue queue : getAllQueues()) {
                                    queue.saveMemory();
                                }
                                return;
                            }
                            if (SetQueue.this.forceChunkSet()) {
                                System.gc();
                            } else {
                                SetQueue.this.runEmptyTasks();
                            }
                            return;
                        }
                    }
                    FaweQueue queue = getNextQueue();
                    if (queue == null || !Fawe.get().getTimer().isAbove(targetTPS)) {
                        return;
                    }
                    if (Thread.currentThread() != Fawe.get().getMainThread()) {
                        throw new IllegalStateException("This shouldn't be possible for placement to occur off the main thread");
                    }
                    long time = Settings.QUEUE.EXTRA_TIME_MS + 50 + Math.min((50 + SetQueue.this.last) - (SetQueue.this.last = System.currentTimeMillis()), SetQueue.this.secondLast - System.currentTimeMillis());
                    // Disable the async catcher as it can't discern async vs parallel
                    boolean parallel = Settings.QUEUE.PARALLEL_THREADS > 1;
                    queue.startSet(parallel);
                    try {
                        if (!queue.next(Settings.QUEUE.PARALLEL_THREADS, getCompleterService(), time)) {
                            queue.runTasks();
                        }
                    } catch (Throwable e) {
                        pool.awaitQuiescence(Settings.QUEUE.DISCARD_AFTER_MS, TimeUnit.MILLISECONDS);
                        completer = new ExecutorCompletionService(pool);
                        e.printStackTrace();
                    }
                    if (pool.getQueuedSubmissionCount() != 0 || pool.getRunningThreadCount() != 0 || pool.getQueuedTaskCount() != 0) {
                        if (Fawe.get().isJava8()) {
                            pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                        } else {
                            pool.shutdown();
                            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
                            pool = new ForkJoinPool();
                            completer = new ExecutorCompletionService(pool);
                        }
                    }
                    secondLast = System.currentTimeMillis();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }, 1);
    }

    public QueueStage getStage(FaweQueue queue) {
        if (activeQueues.contains(queue)) {
            return QueueStage.ACTIVE;
        } else if (inactiveQueues.contains(queue)) {
            return QueueStage.INACTIVE;
        }
        return QueueStage.NONE;
    }

    public boolean isStage(FaweQueue queue, QueueStage stage) {
        switch (stage) {
            case ACTIVE:
                return activeQueues.contains(queue);
            case INACTIVE:
                return inactiveQueues.contains(queue);
            case NONE:
                return !activeQueues.contains(queue) && !inactiveQueues.contains(queue);
        }
        return false;
    }

    public boolean enqueue(FaweQueue queue) {
        inactiveQueues.remove(queue);
        if (queue.size() > 0) {
            if (!activeQueues.contains(queue)) {
                queue.optimize();
                activeQueues.add(queue);
            }
            return true;
        }
        return false;
    }

    public void dequeue(FaweQueue queue) {
        inactiveQueues.remove(queue);
        activeQueues.remove(queue);
        queue.runTasks();
    }

    public Collection<FaweQueue> getAllQueues() {
        ArrayList<FaweQueue> list = new ArrayList<FaweQueue>(activeQueues.size() + inactiveQueues.size());
        list.addAll(inactiveQueues);
        list.addAll(activeQueues);
        return list;
    }

    public Collection<FaweQueue> getActiveQueues() {
        return activeQueues;
    }

    public Collection<FaweQueue> getInactiveQueues() {
        return inactiveQueues;
    }

    public FaweQueue getNewQueue(World world, boolean fast, boolean autoqueue) {
        FaweQueue queue = Fawe.imp().getNewQueue(world, fast);
        if (autoqueue) {
            inactiveQueues.add(queue);
        }
        return queue;
    }

    public FaweQueue getNewQueue(String world, boolean fast, boolean autoqueue) {
        FaweQueue queue = Fawe.imp().getNewQueue(world, fast);
        if (autoqueue) {
            inactiveQueues.add(queue);
        }
        return queue;
    }

    public void flush(FaweQueue queue) {
        queue.startSet(Settings.QUEUE.PARALLEL_THREADS > 1);
        try {
            queue.next(Settings.QUEUE.PARALLEL_THREADS, getCompleterService(), Long.MAX_VALUE);
        } catch (Throwable e) {
            pool.awaitQuiescence(Settings.QUEUE.DISCARD_AFTER_MS, TimeUnit.MILLISECONDS);
            completer = new ExecutorCompletionService(pool);
            MainUtil.handleError(e);
        } finally {
            queue.runTasks();
        }
    }

    public FaweQueue getNextQueue() {
        long now = System.currentTimeMillis();
        while (!activeQueues.isEmpty()) {
            FaweQueue queue = activeQueues.peek();
            if (queue != null && queue.size() > 0) {
                queue.setModified(now);
                return queue;
            } else {
                queue.runTasks();
                activeQueues.poll();
            }
        }
        int size = inactiveQueues.size();
        if (size > 0) {
            Iterator<FaweQueue> iter = inactiveQueues.iterator();
            try {
                int total = 0;
                FaweQueue firstNonEmpty = null;
                while (iter.hasNext()) {
                    FaweQueue queue = iter.next();
                    long age = now - queue.getModified();
                    total += queue.size();
                    if (queue.size() == 0) {
                        if (age > Settings.QUEUE.DISCARD_AFTER_MS) {
                            queue.runTasks();
                            iter.remove();
                        }
                        continue;
                    }
                    if (firstNonEmpty == null) {
                        firstNonEmpty = queue;
                    }
                    if (total > Settings.QUEUE.TARGET_SIZE) {
                        firstNonEmpty.setModified(now);
                        return firstNonEmpty;
                    }
                    if (age > Settings.QUEUE.MAX_WAIT_MS) {
                        queue.setModified(now);
                        return queue;
                    }
                }
            } catch (ConcurrentModificationException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public boolean next() {
        while (activeQueues.size() > 0) {
            FaweQueue queue = activeQueues.poll();
            if (queue != null) {
                final boolean set = queue.next();
                if (set) {
                    activeQueues.add(queue);
                    return set;
                } else {
                    queue.runTasks();
                }
            }
        }
        if (inactiveQueues.size() > 0) {
            ArrayList<FaweQueue> tmp = new ArrayList<>(inactiveQueues);
            if (Settings.QUEUE.MAX_WAIT_MS != -1) {
                long now = System.currentTimeMillis();
                if (lastSuccess == 0) {
                    lastSuccess = now;
                }
                long diff = now - lastSuccess;
                if (diff > Settings.QUEUE.MAX_WAIT_MS) {
                    for (FaweQueue queue : tmp) {
                        boolean result = queue.next();
                        if (result) {
                            return result;
                        }
                    }
                    if (diff > Settings.QUEUE.DISCARD_AFTER_MS) {
                        // These edits never finished
                        for (FaweQueue queue : tmp) {
                            queue.runTasks();
                        }
                        inactiveQueues.clear();
                    }
                    return false;
                }
            }
            if (Settings.QUEUE.TARGET_SIZE != -1) {
                int total = 0;
                for (FaweQueue queue : tmp) {
                    total += queue.size();
                }
                if (total > Settings.QUEUE.TARGET_SIZE) {
                    for (FaweQueue queue : tmp) {
                        boolean result = queue.next();
                        if (result) {
                            return result;
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean forceChunkSet() {
        return next();
    }

    /**
     * Is the this empty
     * @return
     */
    public boolean isEmpty() {
        return activeQueues.size() == 0 && inactiveQueues.size() == 0;
    }

    public void addTask(Runnable whenFree) {
        tasks.add(whenFree);
    }

    /**
     * Add a task to run when it is empty
     * @param whenDone
     * @return
     */
    public boolean addEmptyTask(final Runnable whenDone) {
        if (this.isEmpty()) {
            // Run
            this.runEmptyTasks();
            if (whenDone != null) {
                whenDone.run();
            }
            return true;
        }
        if (whenDone != null) {
            this.emptyTasks.add(whenDone);
        }
        return false;
    }

    private synchronized boolean runEmptyTasks() {
        if (this.emptyTasks.isEmpty()) {
            return false;
        }
        final ConcurrentLinkedDeque<Runnable> tmp = new ConcurrentLinkedDeque<>(this.emptyTasks);
        this.emptyTasks.clear();
        for (final Runnable runnable : tmp) {
            runnable.run();
        }
        return true;
    }
}

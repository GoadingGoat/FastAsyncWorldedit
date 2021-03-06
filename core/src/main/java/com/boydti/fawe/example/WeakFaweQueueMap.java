package com.boydti.fawe.example;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.object.FaweChunk;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.RunnableVal;
import com.boydti.fawe.util.MathMan;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;

public class WeakFaweQueueMap implements IFaweQueueMap {

    private final MappedFaweQueue parent;

    public WeakFaweQueueMap(MappedFaweQueue parent) {
        this.parent = parent;
    }

    /**
     * Map of chunks in the queue
     */
    public ConcurrentHashMap<Long, Reference<FaweChunk>> blocks = new ConcurrentHashMap<Long, Reference<FaweChunk>>(8, 0.9f, 1) {
        @Override
        public Reference<FaweChunk> put(Long key, Reference<FaweChunk> value) {
            if (parent.getProgressTask() != null) {
                try {
                    parent.getProgressTask().run(FaweQueue.ProgressType.QUEUE, size() + 1);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            return super.put(key, value);
        }
    };

    @Override
    public Collection<FaweChunk> getFaweCunks() {
        HashSet<FaweChunk> set = new HashSet<>();
        Iterator<Map.Entry<Long, Reference<FaweChunk>>> iter = blocks.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, Reference<FaweChunk>> entry = iter.next();
            FaweChunk value = entry.getValue().get();
            if (value != null) {
                set.add(value);
            } else {
                Fawe.debug("Skipped modifying chunk due to low memory (1)");
                iter.remove();
            }
        }
        return set;
    }

    @Override
    public void forEachChunk(RunnableVal<FaweChunk> onEach) {
        Iterator<Map.Entry<Long, Reference<FaweChunk>>> iter = blocks.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, Reference<FaweChunk>> entry = iter.next();
            FaweChunk value = entry.getValue().get();
            if (value != null) {
                onEach.run(value);
            } else {
                Fawe.debug("Skipped modifying chunk due to low memory (2)");
                iter.remove();
            }
        }
    }

    @Override
    public FaweChunk getFaweChunk(int cx, int cz) {
        if (cx == lastX && cz == lastZ) {
            return lastWrappedChunk;
        }
        long pair = MathMan.pairInt(cx, cz);
        Reference<FaweChunk> chunkReference = this.blocks.get(pair);
        FaweChunk chunk;
        if (chunkReference == null || (chunk = chunkReference.get()) == null) {
            chunk = this.getNewFaweChunk(cx, cz);
            Reference<FaweChunk> previous = this.blocks.put(pair, new SoftReference(chunk));
            if (previous != null) {
                FaweChunk tmp = previous.get();
                if (tmp != null) {
                    chunk = tmp;
                    this.blocks.put(pair, previous);
                }
            }

        }
        return chunk;
    }

    @Override
    public FaweChunk getCachedFaweChunk(int cx, int cz) {
        if (cx == lastX && cz == lastZ) {
            return lastWrappedChunk;
        }
        long pair = MathMan.pairInt(cx, cz);
        Reference<FaweChunk> reference = this.blocks.get(pair);
        if (reference != null) {
            return reference.get();
        } else {
            return null;
        }
    }

    @Override
    public void add(FaweChunk chunk) {
        long pair = MathMan.pairInt(chunk.getX(), chunk.getZ());
        Reference<FaweChunk> previous = this.blocks.put(pair, new SoftReference<FaweChunk>(chunk));
        if (previous != null) {
            FaweChunk previousChunk = previous.get();
            if (previousChunk != null) {
                blocks.put(pair, previous);
            }
        }
    }


    @Override
    public void clear() {
        blocks.clear();
    }

    @Override
    public int size() {
        return blocks.size();
    }

    private FaweChunk getNewFaweChunk(int cx, int cz) {
        return parent.getFaweChunk(cx, cz);
    }

    private FaweChunk lastWrappedChunk;
    private int lastX = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;

    @Override
    public boolean next(int amount, ExecutorCompletionService pool, long time) {
        lastWrappedChunk = null;
        lastX = Integer.MIN_VALUE;
        lastZ = Integer.MIN_VALUE;
        try {
            int added = 0;
            Iterator<Map.Entry<Long, Reference<FaweChunk>>> iter = blocks.entrySet().iterator();
            if (amount == 1) {
                long start = System.currentTimeMillis();
                do {
                    if (iter.hasNext()) {
                        Map.Entry<Long, Reference<FaweChunk>> entry = iter.next();
                        Reference<FaweChunk> chunkReference = entry.getValue();
                        FaweChunk chunk = chunkReference.get();
                        iter.remove();
                        if (chunk != null) {
                            parent.start(chunk);
                            chunk.call();
                            parent.end(chunk);
                        } else {
                            Fawe.debug("Skipped modifying chunk due to low memory (3)");
                        }
                    } else {
                        break;
                    }
                } while (System.currentTimeMillis() - start < time);
                return !blocks.isEmpty();
            }
            boolean result = true;
            // amount = 8;
            for (int i = 0; i < amount && (result = iter.hasNext()); i++, added++) {
                Map.Entry<Long, Reference<FaweChunk>> item = iter.next();
                Reference<FaweChunk> chunkReference = item.getValue();
                FaweChunk chunk = chunkReference.get();
                iter.remove();
                if (chunk != null) {
                    parent.start(chunk);
                    pool.submit(chunk);
                } else {
                    Fawe.debug("Skipped modifying chunk due to low memory (4)");
                    i--;
                    added--;
                }
            }
            // if result, then submitted = amount
            if (result) {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < time && result) {
                    if (result = iter.hasNext()) {
                        Map.Entry<Long, Reference<FaweChunk>> item = iter.next();
                        Reference<FaweChunk> chunkReference = item.getValue();
                        FaweChunk chunk = chunkReference.get();
                        iter.remove();
                        if (chunk != null) {
                            parent.start(chunk);
                            pool.submit(chunk);
                            FaweChunk fc = ((FaweChunk) pool.take().get());
                            parent.end(fc);
                        }
                    }
                }
            }
            for (int i = 0; i < added; i++) {
                FaweChunk fc = ((FaweChunk) pool.take().get());
                parent.end(fc);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return !blocks.isEmpty();
    }
}

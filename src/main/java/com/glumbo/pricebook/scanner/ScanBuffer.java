package com.glumbo.pricebook.scanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScanBuffer {
    private final Deque<ShopScan> queue = new ArrayDeque<>();
    private final int capacity;

    public ScanBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void enqueue(ShopScan scan) {
        if (queue.size() >= capacity) {
            queue.removeFirst();
        }
        queue.addLast(scan);
    }

    public synchronized List<ShopScan> drain() {
        List<ShopScan> drained = new ArrayList<>(queue);
        queue.clear();
        return drained;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }
}

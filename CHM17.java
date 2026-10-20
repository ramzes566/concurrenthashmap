package concurrenthashmap;

import java.util.concurrent.locks.ReentrantLock;

/**
 * ConcurrentHashMap 1.7 原理：分段锁（Segment）
 *
 * 核心思想：
 *   1. 把一个大 HashMap 切成 N 个小 HashMap（叫 Segment，默认 N=16）
 *   2. 每个 Segment 继承 ReentrantLock，独立加锁
 *   3. put 时用 hash(key) & (N-1) 算出去哪个 Segment 上锁
 *   4. 不同 Segment 的操作互不干扰，并发度 = Segment 数量
 *
 * 演示：简化版只放 get/put 的核心路径，体现"锁粒度 = Segment"
 */
public class CHM17 {

    static final int N = 16;                // 16 个分段
    final Segment[] segments = new Segment[N];

    CHM17() {
        for (int i = 0; i < N; i++) segments[i] = new Segment();
    }

    /** 根据 hash 找到分段（源码里有高位扰动，这里简化） */
    static int segIndex(Object key) {
        return key.hashCode() & (N - 1);
    }

    /** 读：不加锁，直接读 Segment 里的 table（1.7 的 table 用 volatile 保证可见性） */
    Object get(Object key) {
        Segment seg = segments[segIndex(key)];
        // volatile 读 table，拿到链表头再逐个比对
        return seg.tableGet(key);
    }

    /** 写：锁整个 Segment */
    void put(Object key, Object value) {
        Segment seg = segments[segIndex(key)];
        seg.lock();         // ← 锁粒度是 Segment
        try {
            seg.tablePut(key, value);
        } finally {
            seg.unlock();
        }
    }

    /** 一个 Segment 自己就是一个小 HashMap + ReentrantLock */
    static class Segment extends ReentrantLock {
        Node[] table = new Node[4];     // 每个分段内部有自己的桶数组
        int count;

        Object tableGet(Object key) {
            for (Node n : table)
                for (; n != null; n = n.next)
                    if (n.key.equals(key)) return n.value;
            return null;
        }

        void tablePut(Object key, Object value) {
            int idx = key.hashCode() & (table.length - 1);
            for (Node n = table[idx]; n != null; n = n.next)
                if (n.key.equals(key)) { n.value = value; return; }
            // 头插法
            Node newNode = new Node(key, value, table[idx]);
            table[idx] = newNode;
            count++;
        }
    }

    static class Node { Object key, value; Node next;
        Node(Object k, Object v, Node n) { key = k; value = v; next = n; } }

    /** 验证：两个线程分别写不同分段 → 互不阻塞 */
    public static void main(String[] args) throws Exception {
        CHM17 map = new CHM17();
        int perSeg = 100_000;

        long t1 = System.nanoTime();
        Thread tA = new Thread(() -> { for (int i = 0; i < perSeg; i++) map.put("A-" + i, i); });
        Thread tB = new Thread(() -> { for (int i = 0; i < perSeg; i++) map.put("B-" + i, i); });
        tA.start(); tB.start();
        tA.join();  tB.join();
        long cost = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("1.7分段锁: 写入 %,d 对, 耗时 %d ms%n", perSeg * 2, cost);
        // 校验所有 key 都能 get 到
        for (int i = 0; i < perSeg; i++)
            if (map.get("A-" + i) == null || map.get("B-" + i) == null) {
                System.out.println("数据丢失！"); return;
            }
        System.out.println("数据完整，无丢失");
    }
}

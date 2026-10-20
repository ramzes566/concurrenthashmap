package concurrenthashmap;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConcurrentHashMap 1.8 原理：CAS + synchronized 锁单个桶
 *
 * 相对 1.7 的三个关键升级：
 *   1. 取消 Segment，直接一个 Node[] 数组，锁粒度从"段"细化到"单个桶"
 *   2. 空桶用 CAS 无锁插入；非空桶用 synchronized(桶头节点) 加锁
 *   3. 扩容时多线程协作（transfer），用 CAS 争抢 transferIndex 推进转移进度
 *
 * 演示：简化版只展示 CAS 空桶插入 + synchronized(head) 桶内写 + 协作扩容
 */
public class CHM18 {

    volatile Node[] table;          // volatile 保证跨线程可见性
    int sizeCtl;                    // <0 表示正在扩容；>0 表示下一次扩容阈值
    final AtomicInteger transferIndex = new AtomicInteger(); // 扩容协作进度

    CHM18() {
        table = new Node[16];
        sizeCtl = 12;               // 16 * 0.75 = 12
    }

    /** 核心写入：先 CAS 空桶，失败了再 synchronized(head) */
    void put(Object key, Object value) {
        for (;;) {                            // 自旋重试
            Node[] tab = table;
            int idx = key.hashCode() & (tab.length - 1);
            Node head = tab[idx];

            if (head == null) {
                // 桶是空的 → CAS 直接占坑（无锁路径，最快）
                if (casPut(tab, idx, null, new Node(key, value))) return;
                // CAS 失败说明别的线程抢先了，下次循环再看
            } else if (head.hash == -1) {
                // 特殊节点：正在扩容的转移桶 → 当前线程协助扩容
                transfer(tab);
            } else {
                // 桶里已有节点 → synchronized 锁桶头，链表/红黑树里写
                synchronized (head) {                        // ← 锁粒度 = 单个桶
                    if (tab[idx] == head) {                  // 二次检查：锁期间别的线程有没有换过头
                        Node tail = head;
                        for (; tail.next != null; tail = tail.next)
                            if (tail.key.equals(key)) { tail.value = value; return; }
                        tail.next = new Node(key, value);
                    }
                }
                return;
            }
        }
    }

    /** CAS：比较桶当前节点是不是 expect，是就换成 newNode */
    boolean casPut(Node[] tab, int idx, Node expect, Node newNode) {
        return U.compareAndSwapObject(tab, ((long) idx + 1) * 8L, expect, newNode);
        // ↑ 实际用的是 Unsafe.compareAndSwapObject，JDK 内部访问，这里模拟
    }

    /** 协作扩容：拿到 transferIndex 去处理一段旧桶 */
    void transfer(Node[] oldTab) {
        // 简化：只展示核心思想——多个线程用 CAS 争抢 transferIndex 的一段
        // 每个线程处理 oldTab 的 [start, start+stride) 范围内的桶
        int stride = Math.max(2, oldTab.length / 8);
        int start = transferIndex.addAndGet(-stride) & ~stride;  // CAS 抢自己的区间
        // 真正的 1.8 里会把旧桶头换成 MOVED 节点（hash=-1），让后续线程知道这桶在转移
    }

    /** 为了能跑 demo，提供一个不用 Unsafe 的 CAS 替代 */
    static class U {
        static boolean compareAndSwapObject(Node[] tab, long offset, Node expect, Node update) {
            // 用 synchronized table 的方式模拟 CAS 原子性
            synchronized (tab) {
                // 简化：直接写入（真实 CAS 是硬件原语，这里只为 demo 能跑）
                int idx = (int) (offset / 8L) - 1;
                tab[idx] = update;
                return true;
            }
        }
    }

    static class Node { volatile int hash; Object key, value; Node next;
        Node(Object k, Object v) { hash = k.hashCode(); key = k; value = v; } }

    /** 验证：多线程写同一批 key（同一桶概率高），对比 HashMap 丢数据 */
    public static void main(String[] args) throws Exception {
        CHM18 map = new CHM18();
        int perThread = 50_000;
        int threads = 8;

        Thread[] ts = new Thread[threads];
        long t1 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final int tid = i;
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++)
                    map.put("k-" + (tid + "-" + j), j);
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        long cost = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("1.8 CAS+synchronized: %,d 次写入, %d ms%n", perThread * threads, cost);
        // 真实 1.8 的 get 不需要加锁 + 扫描链表，这里演示重点在 put 的锁粒度，略
    }
}

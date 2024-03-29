/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PooledByteBufAllocator.class);

    private static final int DEFAULT_NUM_HEAP_ARENA;

    private static final int DEFAULT_NUM_DIRECT_ARENA;

    private static final int DEFAULT_PAGE_SIZE;

    private static final int DEFAULT_MAX_ORDER; // 8192 << 11 = 16 MiB per chunk

    private static final int DEFAULT_SMALL_CACHE_SIZE;

    private static final int DEFAULT_NORMAL_CACHE_SIZE;

    static final int DEFAULT_MAX_CACHED_BUFFER_CAPACITY;
    private static final int DEFAULT_CACHE_TRIM_INTERVAL;
    private static final long DEFAULT_CACHE_TRIM_INTERVAL_MILLIS;

    private static final boolean DEFAULT_USE_CACHE_FOR_ALL_THREADS;

    private static final int DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT;

    static final int DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK;

    private static final int MIN_PAGE_SIZE = 4096;

    private static final int MAX_CHUNK_SIZE = (int) (((long) Integer.MAX_VALUE + 1) / 2);

    private final Runnable trimTask = new Runnable() {
        @Override
        public void run() {
            PooledByteBufAllocator.this.trimCurrentThreadCache();
        }
    };

    static {
        // 获得默认对齐方式、默认页大小
        int defaultAlignment = SystemPropertyUtil.getInt(
                "io.netty.allocator.directMemoryCacheAlignment", 0);
        int defaultPageSize = SystemPropertyUtil.getInt("io.netty.allocator.pageSize", 8192);

        Throwable pageSizeFallbackCause = null;
        try {
            // 校验并且计算页偏移
            validateAndCalculatePageShifts(defaultPageSize, defaultAlignment);
        } catch (Throwable t) {
            /*
                以下不细究
             */
            pageSizeFallbackCause = t;
            defaultPageSize = 8192;
            defaultAlignment = 0;
        }

        // 设置默认页大小、默认直接内存缓存对齐量
        DEFAULT_PAGE_SIZE = defaultPageSize;
        DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT = defaultAlignment;

        // 获得默认最大订单
        int defaultMaxOrder = SystemPropertyUtil.getInt("io.netty.allocator.maxOrder", 11);
        Throwable maxOrderFallbackCause = null;
        try {
            // 校验并计算块大小
            validateAndCalculateChunkSize(DEFAULT_PAGE_SIZE, defaultMaxOrder);
        } catch (Throwable t) {
            /*
                以下不细究
             */
            maxOrderFallbackCause = t;
            defaultMaxOrder = 11;
        }
        DEFAULT_MAX_ORDER = defaultMaxOrder;

        // Determine reasonable default for nHeapArena and nDirectArena.
        // Assuming each arena has 3 chunks, the pool should not consume more than 50% of max memory.
        /*
            确定堆竞技场和直接竞技场的合理默认值。
            假设每个竞技场有3个块，池子不应该消费超过一半的最大内存。
         */
        final Runtime runtime = Runtime.getRuntime();

        /*
         * We use 2 * available processors by default to reduce contention as we use 2 * available processors for the
         * number of EventLoops in NIO and EPOLL as well. If we choose a smaller number we will run into hot spots as
         * allocation and de-allocation needs to be synchronized on the PoolArena.
         *
         * See https://github.com/netty/netty/issues/3888.
         *
         * 我们默认使用2*可用的处理器以减少竞争，因为我们同样也在nio和epoll里使用2*可用的处理器用以事件循环的数量。
         */
        // 默认最小竞技场数
        final int defaultMinNumArena = NettyRuntime.availableProcessors() * 2;
        // 默认块大小
        final int defaultChunkSize = DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER;

        // 默认堆竞技场数
        DEFAULT_NUM_HEAP_ARENA = Math.max(0,
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numHeapArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                runtime.maxMemory() / defaultChunkSize / 2 / 3)));
        // 默认直接竞技场数
        DEFAULT_NUM_DIRECT_ARENA = Math.max(0,
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numDirectArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                PlatformDependent.maxDirectMemory() / defaultChunkSize / 2 / 3)));

        // cache sizes
        // 缓存大小
        // 默认小缓存大小
        DEFAULT_SMALL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.smallCacheSize", 256);
        // 默认正常缓存大小
        DEFAULT_NORMAL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.normalCacheSize", 64);

        // 32 kb is the default maximum capacity of the cached buffer. Similar to what is explained in
        // 'Scalable memory allocation using jemalloc'
        /*
            32kb是缓存缓冲的默认最大容量。
            类似于使用jemalloc可扩展内存分配的解释。
         */
        // 默认最大缓存缓冲容量
        DEFAULT_MAX_CACHED_BUFFER_CAPACITY = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedBufferCapacity", 32 * 1024);

        // the number of threshold of allocations when cached entries will be freed up if not frequently used
        // 当缓存的条目没被频繁地使用而被释放时，所设置的分配阈值
        DEFAULT_CACHE_TRIM_INTERVAL = SystemPropertyUtil.getInt(
                "io.netty.allocator.cacheTrimInterval", 8192);

        // 如果设置项中有缓存修建间隔毫秒数
        if (SystemPropertyUtil.contains("io.netty.allocation.cacheTrimIntervalMillis")) {
            /*
                以下不细究
             */
            logger.warn("-Dio.netty.allocation.cacheTrimIntervalMillis is deprecated," +
                    " use -Dio.netty.allocator.cacheTrimIntervalMillis");

            if (SystemPropertyUtil.contains("io.netty.allocator.cacheTrimIntervalMillis")) {
                // Both system properties are specified. Use the non-deprecated one.
                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                        "io.netty.allocator.cacheTrimIntervalMillis", 0);
            } else {
                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                        "io.netty.allocation.cacheTrimIntervalMillis", 0);
            }
        } else {
            // 设置默认缓存修剪间隔毫秒数
            DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                    "io.netty.allocator.cacheTrimIntervalMillis", 0);
        }

        // 是否默认为所有线程使用缓存
        DEFAULT_USE_CACHE_FOR_ALL_THREADS = SystemPropertyUtil.getBoolean(
                "io.netty.allocator.useCacheForAllThreads", true);

        // Use 1023 by default as we use an ArrayDeque as backing storage which will then allocate an internal array
        // of 1024 elements. Otherwise we would allocate 2048 and only use 1024 which is wasteful.
        /*
            默认使用1023，因为我们使用数组队列作为备份存储，该数组队列随后将分配1024个元素的内部数组。
            否则，我们将分配2048个，这是浪费。
         */
        // 默认每块最大的缓存字节缓冲
        DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedByteBuffersPerChunk", 1023);

        /*
            以下不细究
         */
        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.allocator.numHeapArenas: {}", DEFAULT_NUM_HEAP_ARENA);
            logger.debug("-Dio.netty.allocator.numDirectArenas: {}", DEFAULT_NUM_DIRECT_ARENA);
            if (pageSizeFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE);
            } else {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE, pageSizeFallbackCause);
            }
            if (maxOrderFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER);
            } else {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER, maxOrderFallbackCause);
            }
            logger.debug("-Dio.netty.allocator.chunkSize: {}", DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER);
            logger.debug("-Dio.netty.allocator.smallCacheSize: {}", DEFAULT_SMALL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.normalCacheSize: {}", DEFAULT_NORMAL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.maxCachedBufferCapacity: {}", DEFAULT_MAX_CACHED_BUFFER_CAPACITY);
            logger.debug("-Dio.netty.allocator.cacheTrimInterval: {}", DEFAULT_CACHE_TRIM_INTERVAL);
            logger.debug("-Dio.netty.allocator.cacheTrimIntervalMillis: {}", DEFAULT_CACHE_TRIM_INTERVAL_MILLIS);
            logger.debug("-Dio.netty.allocator.useCacheForAllThreads: {}", DEFAULT_USE_CACHE_FOR_ALL_THREADS);
            logger.debug("-Dio.netty.allocator.maxCachedByteBuffersPerChunk: {}",
                    DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK);
        }
    }

    public static final PooledByteBufAllocator DEFAULT =
            new PooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    private final PoolArena<byte[]>[] heapArenas;

    private final PoolArena<ByteBuffer>[] directArenas;

    private final int smallCacheSize;

    private final int normalCacheSize;

    private final List<PoolArenaMetric> heapArenaMetrics;

    private final List<PoolArenaMetric> directArenaMetrics;

    private final PoolThreadLocalCache threadCache;

    private final int chunkSize;

    private final PooledByteBufAllocatorMetric metric;

    public PooledByteBufAllocator() {
        this(false);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(boolean preferDirect) {
        // 传参：是否偏向申请直接内存、默认堆竞技场数、默认直接竞技场数、默认页大小、默认最大序
        this(preferDirect, DEFAULT_NUM_HEAP_ARENA, DEFAULT_NUM_DIRECT_ARENA, DEFAULT_PAGE_SIZE, DEFAULT_MAX_ORDER);
    }

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(false, nHeapArena, nDirectArena, pageSize, maxOrder);
    }

    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        // 传参：是否偏向申请直接内存、堆竞技场数、直接竞技场数、页面大小、最大序、极小缓存大小、默认小缓存大小、默认正常缓存大小
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             0, DEFAULT_SMALL_CACHE_SIZE, DEFAULT_NORMAL_CACHE_SIZE);
    }

    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize) {
        // 传参：是否偏向申请直接内存、堆竞技场数、直接竞技场数、页面大小、最大序、小缓存大小、正常缓存大小、是否为所有线程使用缓存，默认直接内存缓存对齐量
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder, smallCacheSize,
             normalCacheSize, DEFAULT_USE_CACHE_FOR_ALL_THREADS, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder, int tinyCacheSize,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads);
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * @deprecated use
     * {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean, int)}
     */
    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads, directMemoryCacheAlignment);
    }

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        // 调用父类的构造方法，传参：是否偏向申请直接内存
        super(preferDirect);

        // 设置池化线程本地缓存、小缓存大小、正常缓存大小
        threadCache = new PoolThreadLocalCache(useCacheForAllThreads);
        this.smallCacheSize = smallCacheSize;
        this.normalCacheSize = normalCacheSize;

        // 如果直接内存缓存对齐量不为0
        if (directMemoryCacheAlignment != 0) {
            /*
                以下不细究
             */
            if (!PlatformDependent.hasAlignDirectByteBuffer()) {
                throw new UnsupportedOperationException("Buffer alignment is not supported. " +
                        "Either Unsafe or ByteBuffer.alignSlice() must be available.");
            }

            // Ensure page size is a whole multiple of the alignment, or bump it to the next whole multiple.
            pageSize = (int) PlatformDependent.align(pageSize, directMemoryCacheAlignment);
        }

        // 校验并且计算块大小
        chunkSize = validateAndCalculateChunkSize(pageSize, maxOrder);

        // 检查堆竞技场数和直接竞技场数、直接内存缓冲对齐量的正负性
        checkPositiveOrZero(nHeapArena, "nHeapArena");
        checkPositiveOrZero(nDirectArena, "nDirectArena");
        checkPositiveOrZero(directMemoryCacheAlignment, "directMemoryCacheAlignment");

        // 如果直接内存缓存对齐量大于0，并且该池化字节缓冲分配器不支持直接内存缓存对齐量
        if (directMemoryCacheAlignment > 0 && !isDirectMemoryCacheAlignmentSupported()) {
            // 以下不细究
            throw new IllegalArgumentException("directMemoryCacheAlignment is not supported");
        }

        // 如果满足以下条件
        if ((directMemoryCacheAlignment & -directMemoryCacheAlignment) != directMemoryCacheAlignment) {
            // 以下不细究
            throw new IllegalArgumentException("directMemoryCacheAlignment: "
                    + directMemoryCacheAlignment + " (expected: power of two)");
        }

        // 校验并且计算页面偏移
        int pageShifts = validateAndCalculatePageShifts(pageSize, directMemoryCacheAlignment);

        // 如果堆竞技场数量大于0
        if (nHeapArena > 0) {
            // 实例化一个竞技场数组，当做堆竞技场
            heapArenas = newArenaArray(nHeapArena);

            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(heapArenas.length);
            // 实例化堆竞技场数组
            for (int i = 0; i < heapArenas.length; i ++) {
                // 实例化一个堆竞技场，并赋值到对应堆竞技场数组位置
                PoolArena.HeapArena arena = new PoolArena.HeapArena(this,
                        pageSize, pageShifts, chunkSize,
                        directMemoryCacheAlignment);
                heapArenas[i] = arena;

                // 将该堆竞技场添加到池化竞技场标准集合中
                metrics.add(arena);
            }
            heapArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            /*
                以下不细究
             */
            heapArenas = null;
            heapArenaMetrics = Collections.emptyList();
        }

        // 如果直接竞技场的个数大于0
        if (nDirectArena > 0) {
            // 实例化直接竞技场数组、构建池化竞技场标准集合
            directArenas = newArenaArray(nDirectArena);
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(directArenas.length);
            for (int i = 0; i < directArenas.length; i ++) {
                // 实例化一个直接竞技场
                PoolArena.DirectArena arena = new PoolArena.DirectArena(
                        this, pageSize, pageShifts, chunkSize, directMemoryCacheAlignment);
                directArenas[i] = arena;
                metrics.add(arena);
            }
            directArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            /*
                以下不细究
             */
            directArenas = null;
            directArenaMetrics = Collections.emptyList();
        }

        // 实例化并赋值池化字节缓冲分配器标准
        metric = new PooledByteBufAllocatorMetric(this);
    }

    @SuppressWarnings("unchecked")
    private static <T> PoolArena<T>[] newArenaArray(int size) {
        return new PoolArena[size];
    }

    /**
     * 校验和计算页偏移
     *
     * @param pageSize 页面大小
     * @param alignment 对齐
     * @return 校验和计算页偏移
     */
    private static int validateAndCalculatePageShifts(int pageSize, int alignment) {
        // 如果页面大小小于最小页面大小，则抛出违规参数异常
        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: " + MIN_PAGE_SIZE + ')');
        }

        // 如果入参页面大小不是2的幂，则抛出违规参数异常
        if ((pageSize & pageSize - 1) != 0) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: power of 2)");
        }

        // 如果页面大小小于对齐量，则抛出违规参数异常
        if (pageSize < alignment) {
            throw new IllegalArgumentException("Alignment cannot be greater than page size. " +
                    "Alignment: " + alignment + ", page size: " + pageSize + '.');
        }

        // Logarithm base 2. At this point we know that pageSize is a power of two.
        /*
            基于2的算法。
            此时我们知道页面大小是2的幂。
         */
        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize);
    }

    private static int validateAndCalculateChunkSize(int pageSize, int maxOrder) {
        // 如果最大顺序超过14，则抛出违规参数异常
        if (maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder: " + maxOrder + " (expected: 0-14)");
        }

        // Ensure the resulting chunkSize does not overflow.
        // 确保计算出来的块大小不会溢出。
        int chunkSize = pageSize;
        for (int i = maxOrder; i > 0; i --) {
            // 如果块大小超过最大块大小的一半
            if (chunkSize > MAX_CHUNK_SIZE / 2) {
                // 不细究
                throw new IllegalArgumentException(String.format(
                        "pageSize (%d) << maxOrder (%d) must not exceed %d", pageSize, maxOrder, MAX_CHUNK_SIZE));
            }
            // 块大小扩容一倍
            chunkSize <<= 1;
        }

        // 返回块大小
        return chunkSize;
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        PoolThreadCache cache = threadCache.get();
        PoolArena<byte[]> heapArena = cache.heapArena;

        final ByteBuf buf;
        if (heapArena != null) {
            buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            buf = PlatformDependent.hasUnsafe() ?
                    new UnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        return toLeakAwareBuffer(buf);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        // 拿到池化线程缓冲
        PoolThreadCache cache = threadCache.get();
        // 拿到池线程缓冲中的直接竞技场
        PoolArena<ByteBuffer> directArena = cache.directArena;

        // 分配字节缓冲
        final ByteBuf buf;
        if (directArena != null) {
            // 由直接竞技场分配出字节缓冲
            buf = directArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            // 不细究
            buf = PlatformDependent.hasUnsafe() ?
                    UnsafeByteBufUtil.newUnsafeDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        // 将分配出的缓冲转换为可感知泄露的缓冲
        return toLeakAwareBuffer(buf);
    }

    /**
     * Default number of heap arenas - System Property: io.netty.allocator.numHeapArenas - default 2 * cores
     */
    public static int defaultNumHeapArena() {
        return DEFAULT_NUM_HEAP_ARENA;
    }

    /**
     * Default number of direct arenas - System Property: io.netty.allocator.numDirectArenas - default 2 * cores
     */
    public static int defaultNumDirectArena() {
        return DEFAULT_NUM_DIRECT_ARENA;
    }

    /**
     * Default buffer page size - System Property: io.netty.allocator.pageSize - default 8192
     */
    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * Default maximum order - System Property: io.netty.allocator.maxOrder - default 11
     */
    public static int defaultMaxOrder() {
        return DEFAULT_MAX_ORDER;
    }

    /**
     * Default thread caching behavior - System Property: io.netty.allocator.useCacheForAllThreads - default true
     */
    public static boolean defaultUseCacheForAllThreads() {
        return DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    }

    /**
     * Default prefer direct - System Property: io.netty.noPreferDirect - default false
     */
    public static boolean defaultPreferDirect() {
        return PlatformDependent.directBufferPreferred();
    }

    /**
     * Default tiny cache size - default 0
     *
     * @deprecated Tiny caches have been merged into small caches.
     */
    @Deprecated
    public static int defaultTinyCacheSize() {
        return 0;
    }

    /**
     * Default small cache size - System Property: io.netty.allocator.smallCacheSize - default 256
     */
    public static int defaultSmallCacheSize() {
        return DEFAULT_SMALL_CACHE_SIZE;
    }

    /**
     * Default normal cache size - System Property: io.netty.allocator.normalCacheSize - default 64
     */
    public static int defaultNormalCacheSize() {
        return DEFAULT_NORMAL_CACHE_SIZE;
    }

    /**
     * Return {@code true} if direct memory cache alignment is supported, {@code false} otherwise.
     */
    public static boolean isDirectMemoryCacheAlignmentSupported() {
        return PlatformDependent.hasUnsafe();
    }

    @Override
    public boolean isDirectBufferPooled() {
        // 不细究
        // 如果直接竞技场数不为null
        return directArenas != null;
    }

    /**
     * Returns {@code true} if the calling {@link Thread} has a {@link ThreadLocal} cache for the allocated
     * buffers.
     */
    @Deprecated
    public boolean hasThreadLocalCache() {
        return threadCache.isSet();
    }

    /**
     * Free all cached buffers for the calling {@link Thread}.
     */
    @Deprecated
    public void freeThreadLocalCache() {
        threadCache.remove();
    }

    final class PoolThreadLocalCache extends FastThreadLocal<PoolThreadCache> {

        private final boolean useCacheForAllThreads;

        PoolThreadLocalCache(boolean useCacheForAllThreads) {
            // 该缓存是否为所有线程所使用
            this.useCacheForAllThreads = useCacheForAllThreads;
        }

        @Override
        protected synchronized PoolThreadCache initialValue() {
            // 获得该池化字节缓冲分配器最少使用的堆竞技场
            final PoolArena<byte[]> heapArena = leastUsedArena(heapArenas);
            // 获得该池化字节缓冲分配器最少使用的直接竞技场
            final PoolArena<ByteBuffer> directArena = leastUsedArena(directArenas);

            // 获得当前线程
            final Thread current = Thread.currentThread();
            // 如果当前池化线程缓存支持对所有线程使用缓存，或者当前线程是快速线程本地线程实例
            if (useCacheForAllThreads || current instanceof FastThreadLocalThread) {
                // 实例出一个池化线程缓存
                final PoolThreadCache cache = new PoolThreadCache(
                        heapArena, directArena, smallCacheSize, normalCacheSize,
                        DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL);

                // 如果默认缓存修剪间隔毫秒时间大于0
                if (DEFAULT_CACHE_TRIM_INTERVAL_MILLIS > 0) {
                    /*
                        不细究
                     */
                    final EventExecutor executor = ThreadExecutorMap.currentExecutor();
                    if (executor != null) {
                        executor.scheduleAtFixedRate(trimTask, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS,
                                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }

                // 返回池化线程缓存
                return cache;
            }

            // 不细究
            // No caching so just use 0 as sizes.
            return new PoolThreadCache(heapArena, directArena, 0, 0, 0, 0);
        }

        @Override
        protected void onRemoval(PoolThreadCache threadCache) {
            threadCache.free(false);
        }

        private <T> PoolArena<T> leastUsedArena(PoolArena<T>[] arenas) {
            // 如果入参的堆竞技场为null或数量为0，则直接返回
            if (arenas == null || arenas.length == 0) {
                // 不细究
                return null;
            }

            // 找到该池竞技场数组中线程缓存数最小的池竞技场
            PoolArena<T> minArena = arenas[0];
            for (int i = 1; i < arenas.length; i++) {
                PoolArena<T> arena = arenas[i];
                if (arena.numThreadCaches.get() < minArena.numThreadCaches.get()) {
                    minArena = arena;
                }
            }

            // 返回最小线程缓存数的池竞技场
            return minArena;
        }
    }

    @Override
    public PooledByteBufAllocatorMetric metric() {
        return metric;
    }

    /**
     * Return the number of heap arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numHeapArenas()}.
     */
    @Deprecated
    public int numHeapArenas() {
        return heapArenaMetrics.size();
    }

    /**
     * Return the number of direct arenas.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numDirectArenas()}.
     */
    @Deprecated
    public int numDirectArenas() {
        return directArenaMetrics.size();
    }

    /**
     * Return a {@link List} of all heap {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#heapArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> heapArenas() {
        return heapArenaMetrics;
    }

    /**
     * Return a {@link List} of all direct {@link PoolArenaMetric}s that are provided by this pool.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#directArenas()}.
     */
    @Deprecated
    public List<PoolArenaMetric> directArenas() {
        return directArenaMetrics;
    }

    /**
     * Return the number of thread local caches used by this {@link PooledByteBufAllocator}.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#numThreadLocalCaches()}.
     */
    @Deprecated
    public int numThreadLocalCaches() {
        PoolArena<?>[] arenas = heapArenas != null ? heapArenas : directArenas;
        if (arenas == null) {
            return 0;
        }

        int total = 0;
        for (PoolArena<?> arena : arenas) {
            total += arena.numThreadCaches.get();
        }

        return total;
    }

    /**
     * Return the size of the tiny cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#tinyCacheSize()}.
     */
    @Deprecated
    public int tinyCacheSize() {
        return 0;
    }

    /**
     * Return the size of the small cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#smallCacheSize()}.
     */
    @Deprecated
    public int smallCacheSize() {
        return smallCacheSize;
    }

    /**
     * Return the size of the normal cache.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#normalCacheSize()}.
     */
    @Deprecated
    public int normalCacheSize() {
        return normalCacheSize;
    }

    /**
     * Return the chunk size for an arena.
     *
     * @deprecated use {@link PooledByteBufAllocatorMetric#chunkSize()}.
     */
    @Deprecated
    public final int chunkSize() {
        return chunkSize;
    }

    final long usedHeapMemory() {
        return usedMemory(heapArenas);
    }

    final long usedDirectMemory() {
        return usedMemory(directArenas);
    }

    private static long usedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numActiveBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    final PoolThreadCache threadCache() {
        PoolThreadCache cache =  threadCache.get();
        assert cache != null;
        return cache;
    }

    /**
     * Trim thread local cache for the current {@link Thread}, which will give back any cached memory that was not
     * allocated frequently since the last trim operation.
     *
     * Returns {@code true} if a cache for the current {@link Thread} exists and so was trimmed, false otherwise.
     */
    public boolean trimCurrentThreadCache() {
        PoolThreadCache cache = threadCache.getIfExists();
        if (cache != null) {
            cache.trim();
            return true;
        }
        return false;
    }

    /**
     * Returns the status of the allocator (which contains all metrics) as string. Be aware this may be expensive
     * and so should not called too frequently.
     */
    public String dumpStats() {
        int heapArenasLen = heapArenas == null ? 0 : heapArenas.length;
        StringBuilder buf = new StringBuilder(512)
                .append(heapArenasLen)
                .append(" heap arena(s):")
                .append(StringUtil.NEWLINE);
        if (heapArenasLen > 0) {
            for (PoolArena<byte[]> a: heapArenas) {
                buf.append(a);
            }
        }

        int directArenasLen = directArenas == null ? 0 : directArenas.length;

        buf.append(directArenasLen)
           .append(" direct arena(s):")
           .append(StringUtil.NEWLINE);
        if (directArenasLen > 0) {
            for (PoolArena<ByteBuffer> a: directArenas) {
                buf.append(a);
            }
        }

        return buf.toString();
    }
}

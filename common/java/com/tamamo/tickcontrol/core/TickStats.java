
package com.tamamo.tickcontrol.core;

import java.util.Arrays;

/**
 * 与版本/加载器无关的每刻耗时统计，用于 {@code /tick query}。
 *
 * <p>原版 1.20.1 的 {@code MinecraftServer.tickTimes}（{@code long[100]}`）只有平均值，
 * 没有百分位；这里复刻 1.20.3+ {@code /tick query} 的输出，自行维护环形缓冲并计算
 * 平均值与百分位。构造后所有读写都在服务器主线程，因此不需要同步。
 */
public final class TickStats {

    private final long[] samplesNanos;
    private int cursor = 0;
    private int filled = 0;

    public TickStats(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.samplesNanos = new long[capacity];
    }

    public int capacity() {
        return this.samplesNanos.length;
    }

    /** 已采集的样本数（最多等于容量）。 */
    public int size() {
        return this.filled;
    }

    public boolean isEmpty() {
        return this.filled == 0;
    }

    public void clear() {
        Arrays.fill(this.samplesNanos, 0L);
        this.cursor = 0;
        this.filled = 0;
    }

    /** 记录一次服务器 tick 的耗时（纳秒）。 */
    public void record(long nanos) {
        this.samplesNanos[this.cursor] = Math.max(0L, nanos);
        this.cursor = (this.cursor + 1) % this.samplesNanos.length;
        if (this.filled < this.samplesNanos.length) {
            this.filled++;
        }
    }

    /** 从 MinecraftServer.tickTimes 这类既有数组导入样本（例如刚加载统计时）。 */
    public void importSamples(long[] times) {
        if (times == null || times.length == 0) {
            return;
        }
        for (long time : times) {
            // 1.20.1 的 tickTimes 里未采集到的槽位是 0，跳过避免污染平均
            if (time > 0L) {
                this.record(time);
            }
        }
    }

    /**
     * 用外部数组<b>整体替换</b>当前窗口。
     *
     * <p>服务器每刻都会把耗时写进 {@code MinecraftServer.tickTimes} 的同一个环形数组，
     * 因此这里不能累加（否则同一样本会被重复计数、撑爆窗口），而是每次清空后重填。
     */
    public void replaceSamples(long[] times) {
        this.clear();
        this.importSamples(times);
    }

    public double averageNanos() {
        if (this.filled == 0) {
            return 0.0D;
        }
        long sum = 0L;
        for (int i = 0; i < this.filled; i++) {
            sum += this.samplesNanos[i];
        }
        return (double) sum / (double) this.filled;
    }

    public double averageMillis() {
        return averageNanos() / 1_000_000.0D;
    }

    /** 最小样本（毫秒）；无样本时返回 0。 */
    public double minMillis() {
        return percentileMillis(0.0D);
    }

    /** 最大样本（毫秒）；无样本时返回 0。 */
    public double maxMillis() {
        return percentileMillis(1.0D);
    }

    /**
     * 线性插值百分位（与常见 P95/P99 定义一致）。
     *
     * @param fraction 0.0 ~ 1.0
     */
    public double percentileMillis(double fraction) {
        if (this.filled == 0) {
            return 0.0D;
        }
        double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
        long[] sorted = Arrays.copyOf(this.samplesNanos, this.filled);
        Arrays.sort(sorted);
        double position = clamped * (double) (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower] / 1_000_000.0D;
        }
        double weight = position - lower;
        double value = sorted[lower] * (1.0D - weight) + sorted[upper] * weight;
        return value / 1_000_000.0D;
    }

    /** 以 0.01ms 精度格式化单个毫秒值，与原版 {@code TickCommand.format} 的风格一致。 */
    public static String formatMillis(double millis) {
        return String.format(java.util.Locale.ROOT, "%.2f", millis);
    }

    /**
     * 目标速率下「一刻应花多少毫秒」。
     *
     * <p>注意这是相对于 20 TPS 的等效值：速率越高，允许的单刻耗时越短。
     */
    public static double targetMillisPerTick(float tickRate) {
        return TickState.MILLIS_PER_TICK * TickState.DEFAULT_TICK_RATE / TickState.clampRate(tickRate);
    }

    /** 当前平均耗时是否已经跟不上目标速率。 */
    public boolean isLagging(float tickRate) {
        if (this.filled == 0) {
            return false;
        }
        return this.averageMillis() > targetMillisPerTick(tickRate);
    }
}

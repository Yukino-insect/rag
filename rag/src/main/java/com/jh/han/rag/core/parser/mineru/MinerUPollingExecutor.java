
package com.jh.han.rag.core.parser.mineru;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jh.han.rag.framework.exception.ServiceException;
import com.jh.han.rag.knowledge.dao.entity.KnowledgeDocumentMineruTaskDO;
import com.jh.han.rag.knowledge.dao.mapper.KnowledgeDocumentMineruTaskMapper;
import com.jh.han.rag.knowledge.enums.KnowledgeDocumentMineruTaskStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MinerU 共享轮询调度器：把 HTTP 轮询从业务消费者线程剥离到独立调度池
 * <p>
 * 消费者线程仍阻塞在 await 上，只是轮询动作交给 {@link #SCHEDULER_THREADS} 个共享调度线程执行，
 * 数百个 outstanding 任务共用；全局 outstanding 并发由 {@link MinerUDocumentParser} 的分布式信号量控制
 */
@Slf4j
@Component
public class MinerUPollingExecutor {

    private static final int SCHEDULER_THREADS = 4;
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    private final MinerUTaskService minerUTaskService;
    private final MinerUProperties properties;

    private ScheduledExecutorService scheduler;

    public MinerUPollingExecutor(MinerUTaskService minerUTaskService,
                                 MinerUProperties properties) {
        this.minerUTaskService = minerUTaskService;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS, namedFactory());
        log.info("MinerUPollingExecutor 启动: schedulerThreads={}", SCHEDULER_THREADS);
    }

    public CompletableFuture<Void> submitAndAwait(String taskId,
                                                  Duration slidingTimeout,
                                                  Duration absoluteTimeout) {
        if (!StringUtils.hasText(taskId)) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ServiceException("taskId 不能为空"));
            return failed;
        }
        if (slidingTimeout == null || slidingTimeout.isZero() || slidingTimeout.isNegative()) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ServiceException("slidingTimeout 必须大于 0"));
            return failed;
        }
        if (absoluteTimeout == null || absoluteTimeout.isZero() || absoluteTimeout.isNegative()) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ServiceException("absoluteTimeout 必须大于 0"));
            return failed;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        ScheduledFuture<?>[] holder = new ScheduledFuture[1];
        Instant startedAt = Instant.now();
        Instant absoluteDeadline = startedAt.plus(absoluteTimeout);
        AtomicReference<Instant> slidingDeadlineRef =
                new AtomicReference<>(minInstant(startedAt.plus(slidingTimeout), absoluteDeadline));
        Runnable poll = () -> doPoll(taskId, future, slidingDeadlineRef, absoluteDeadline, slidingTimeout, holder);

        // 最小间隔 100 ms，生产配置 5s
        long intervalMs = Math.max(100L, properties.getPollIntervalSeconds() * 1000L);
        holder[0] = scheduler.scheduleAtFixedRate(poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // future 完成时，兜底取消调度任务
        future.whenComplete((status, throwable) -> {
            ScheduledFuture<?> task = holder[0];
            if (task != null) task.cancel(false);
        });

        return future;
    }

    private void doPoll(String taskId,
                        CompletableFuture<Void> future,
                        AtomicReference<Instant> slidingDeadlineRef,
                        Instant absoluteDeadline,
                        Duration slidingTimeout,
                        ScheduledFuture<?>[] holder) {
        if (future.isDone()) return;

        Instant now = Instant.now();
        if (now.isAfter(absoluteDeadline)) {
            timeout(taskId, future, holder,
                    "MinerU 任务超时(已达到绝对上限) taskId=" + taskId + " max=" + absoluteDeadline);
            return;
        }

        // 如果服务器不存在该任务
        // 或者该任务正在执行中
        // 直接返回
        KnowledgeDocumentMineruTaskStatus taskStatus = minerUTaskService.getTaskStatus(taskId);
        if (taskStatus.equals(KnowledgeDocumentMineruTaskStatus.RUNNING)) {
            // Python 仍在执行中时刷新滑动超时窗口，但不允许突破绝对上限
            slidingDeadlineRef.set(minInstant(now.plus(slidingTimeout), absoluteDeadline));
            return;
        }

        KnowledgeDocumentMineruTaskDO mineruTaskDO = minerUTaskService.findMineruTaskByTaskId(taskId);
        String status = mineruTaskDO.getStatus();
        if (status.equalsIgnoreCase(KnowledgeDocumentMineruTaskStatus.CALLBACK_RECEIVED.getCode())) {
            complete(future, holder);
        } else if (status.equalsIgnoreCase(KnowledgeDocumentMineruTaskStatus.FAILED.getCode())) {
            completeExceptionally(future, new ServiceException(
                    "MinerU 任务失败 taskId=" + taskId + " err=" + mineruTaskDO.getErrorMessage()
            ), holder);
        } else if (Instant.now().isAfter(slidingDeadlineRef.get())) {
            timeout(taskId, future, holder,
                    "MinerU 任务超时(滑动窗口内未等到运行心跳或完成回调) taskId=" + taskId
                            + " slidingDeadline=" + slidingDeadlineRef.get()
                            + " absoluteDeadline=" + absoluteDeadline);
        }
    }

    private void timeout(String taskId,
                         CompletableFuture<Void> future,
                         ScheduledFuture<?>[] holder,
                         String message) {
        completeExceptionally(future, new TimeoutException(message), holder);
        minerUTaskService.markFailed(taskId, message);
    }

    private void complete(CompletableFuture<Void> future,
                          ScheduledFuture<?>[] holder) {
        if (future.complete(null)) {
            cancelPolling(holder);
        }
    }

    private void completeExceptionally(CompletableFuture<Void> future,
                                       Throwable error,
                                       ScheduledFuture<?>[] holder) {
        if (future.completeExceptionally(error)) {
            cancelPolling(holder);
        }
    }

    private void cancelPolling(ScheduledFuture<?>[] holder) {
        ScheduledFuture<?> task = holder[0];
        if (task != null) {
            task.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler == null) {
            return;
        }
        log.info("MinerUPollingExecutor 优雅停机中，等待 active 任务最多 {}s", SHUTDOWN_AWAIT_SECONDS);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("MinerUPollingExecutor 强制停机");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private static ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "minerU-poll-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    private static Instant minInstant(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}

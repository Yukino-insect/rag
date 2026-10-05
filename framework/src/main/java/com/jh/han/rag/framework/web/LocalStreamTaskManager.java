package com.jh.han.rag.framework.web;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单体应用内的流式任务取消管理器。
 * <p>
 * 任务状态仅保存在当前 JVM 中，适用于 SSE 连接与处理任务位于同一应用实例的部署方式。
 * 取消时先中断上游流，再执行 SSE 收尾回调；两类动作均保证至多执行一次。
 */
@Slf4j
@Component
public class LocalStreamTaskManager {

    private static final Duration TASK_TTL = Duration.ofMinutes(30);

    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(TASK_TTL)
            .maximumSize(10_000)
            .build();

    /**
     * 注册取消后的收尾回调，负责持久化已累计内容并结束 SSE 响应。
     */
    public void register(String taskId, Runnable onCancelFinalizer) {
        StreamTaskInfo taskInfo = getOrCreate(taskId); // 注册任务 ID
        taskInfo.setFinalizer(onCancelFinalizer); // 注册取消的回调函数
    }

    /**
     * 绑定上游流的中断动作。任务已经取消时，立即执行该动作。
     */
    public void bindHandle(String taskId, Runnable cancelAction) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.setCancelAction(cancelAction);
    }

    /**
     * 判断当前应用实例中的任务是否已取消。
     */
    public boolean isCancelled(String taskId) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        return taskInfo != null && taskInfo.cancelled.get();
    }

    /**
     * 取消当前应用实例中的流式任务。
     * <p>
     * 单体模式没有跨节点转发需求；为兼容注册与停止请求的并发时序，尚未注册完成的任务
     * 会先在本地保留取消状态，待回调或上游句柄完成绑定后立即处理。
     */
    public void cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        getOrCreate(taskId).cancel();
    }

    /**
     * 正常完成或异常结束后清理当前 JVM 中的任务状态。
     */
    public void unregister(String taskId) {
        if (taskId != null) {
            tasks.invalidate(taskId);
        }
    }

    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    private static final class StreamTaskInfo {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean cancelActionExecuted = new AtomicBoolean(false);
        private final AtomicBoolean finalizerExecuted = new AtomicBoolean(false);
        private volatile Runnable cancelAction;
        private volatile Runnable finalizer;

        private void setFinalizer(Runnable finalizer) {
            this.finalizer = finalizer;
            runFinalizerIfCancelled();
        }

        private void setCancelAction(Runnable cancelAction) {
            this.cancelAction = cancelAction;
            runCancelActionIfCancelled();
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            runCancelActionIfCancelled();
            runFinalizerIfCancelled();
        }

        private void runCancelActionIfCancelled() {
            if (cancelled.get() && cancelAction != null && cancelActionExecuted.compareAndSet(false, true)) {
                runSafely(cancelAction, "中断上游流");
            }
        }

        private void runFinalizerIfCancelled() {
            if (cancelled.get() && finalizer != null && finalizerExecuted.compareAndSet(false, true)) {
                runSafely(finalizer, "执行取消收尾");
            }
        }

        private void runSafely(Runnable action, String actionName) {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.warn("流式任务{}失败", actionName, ex);
            }
        }
    }
}

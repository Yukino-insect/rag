package com.jh.han.rag.framework.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStreamTaskManagerTest {

    @Test
    void cancelsUpstreamBeforeFinalizingAndOnlyOnce() {
        LocalStreamTaskManager taskManager = new LocalStreamTaskManager();
        List<String> actions = new ArrayList<>();

        taskManager.register("task-1", () -> actions.add("finalizer"));
        taskManager.bindHandle("task-1", () -> actions.add("cancel-action"));

        taskManager.cancel("task-1");
        taskManager.cancel("task-1");

        assertEquals(List.of("cancel-action", "finalizer"), actions);
        assertTrue(taskManager.isCancelled("task-1"));
    }

    @Test
    void executesActionsBoundAfterCancellation() {
        LocalStreamTaskManager taskManager = new LocalStreamTaskManager();
        List<String> actions = new ArrayList<>();

        taskManager.cancel("task-1");
        taskManager.bindHandle("task-1", () -> actions.add("cancel-action"));
        taskManager.register("task-1", () -> actions.add("finalizer"));

        assertEquals(List.of("cancel-action", "finalizer"), actions);
    }

    @Test
    void unregisterRemovesTaskState() {
        LocalStreamTaskManager taskManager = new LocalStreamTaskManager();

        taskManager.register("task-1", () -> {
        });
        taskManager.unregister("task-1");

        assertTrue(!taskManager.isCancelled("task-1"));
    }
}

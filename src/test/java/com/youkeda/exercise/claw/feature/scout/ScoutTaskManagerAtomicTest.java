package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.store.ScoutTaskStore;
import com.youkeda.exercise.claw.feature.scout.task.ScoutTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoutTaskManagerAtomicTest {

    @Test
    void concurrentSubmissionsCreateOnlyOneActiveTask() throws Exception {
        ScoutTaskStore taskStore = mock(ScoutTaskStore.class);
        List<ScoutTask> tasks = new CopyOnWriteArrayList<>();
        when(taskStore.findAll()).thenAnswer(invocation -> new ArrayList<>(tasks));
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(taskStore).save(any(ScoutTask.class));
        ScoutTaskManager manager = new ScoutTaskManager(taskStore);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return manager.createTaskIfNoActive("task-1", "AI");
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return manager.createTaskIfNoActive("task-2", "AI");
            });
            start.countDown();

            int created = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, created);
            assertEquals(1, tasks.size());
        } finally {
            executor.shutdownNow();
        }
    }
}

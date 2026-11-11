package com.velocitypowered.api.scheduler;
public interface Scheduler {
  TaskBuilder buildTask(Object plugin, Runnable r);
  interface TaskBuilder {
    TaskBuilder delay(long t, java.util.concurrent.TimeUnit u);
    TaskBuilder repeat(long t, java.util.concurrent.TimeUnit u);
    ScheduledTask schedule();
  }
}

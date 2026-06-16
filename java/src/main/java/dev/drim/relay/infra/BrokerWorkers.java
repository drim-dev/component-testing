package dev.drim.relay.infra;

import org.springframework.context.SmartLifecycle;

/**
 * Owns the lifecycle of the two background broker consumers (feed-fanout + notification worker),
 * starting them once the context is up and stopping them on shutdown. Keeping both behind one
 * {@link SmartLifecycle} means the harness gets a settled, draining pipeline as soon as the
 * application context is ready.
 */
public class BrokerWorkers implements SmartLifecycle {
  private final FeedConsumer feedConsumer;
  private final NotificationWorker notificationWorker;
  private volatile boolean running = false;

  public BrokerWorkers(FeedConsumer feedConsumer, NotificationWorker notificationWorker) {
    this.feedConsumer = feedConsumer;
    this.notificationWorker = notificationWorker;
  }

  @Override
  public void start() {
    feedConsumer.start();
    notificationWorker.start();
    running = true;
  }

  @Override
  public void stop() {
    feedConsumer.stop();
    notificationWorker.stop();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}

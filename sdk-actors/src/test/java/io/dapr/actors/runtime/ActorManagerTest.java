/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */

package io.dapr.actors.runtime;

import io.dapr.actors.ActorId;
import io.dapr.serializer.DefaultObjectSerializer;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Actor Manager
 */
public class ActorManagerTest {

  private static final ActorObjectSerializer INTERNAL_SERIALIZER = new ActorObjectSerializer();

  private static final AtomicInteger ACTOR_ID_COUNT = new AtomicInteger();

  interface MyActor {
    String say(String something);

    int getCount();

    void incrementCount(int delta);
  }

  public static class NotRemindableActor extends AbstractActor {
    public NotRemindableActor(ActorRuntimeContext runtimeContext, ActorId id) {
      super(runtimeContext, id);
    }
  }

  @ActorType(name = "MyActor")
  public static class MyActorImpl extends AbstractActor implements MyActor, Remindable<String> {

    private int timeCount = 0;

    @Override
    public String say(String something) {
      return executeSayMethod(something);
    }

    @Override
    public int getCount() {
      return this.timeCount;
    }

    @Override
    public void incrementCount(int delta) {
      this.timeCount = timeCount + delta;
    }

    public MyActorImpl(ActorRuntimeContext runtimeContext, ActorId id) {
      super(runtimeContext, id);
      super.registerActorTimer(
        "count",
        "incrementCount",
        2,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1)
      ).block();
    }

    @Override
    public Class<String> getStateType() {
      return String.class;
    }

    @Override
    public Mono<Void> receiveReminder(String reminderName, String state, Duration dueTime, Duration period) {
      return Mono.empty();
    }
  }

  private ActorRuntimeContext<MyActorImpl> context = createContext(MyActorImpl.class);

  private ActorManager<MyActorImpl> manager = new ActorManager<>(context);

  @Test(expected = IllegalArgumentException.class)
  public void invokeBeforeActivate() throws Exception {
    ActorId actorId = newActorId();
    String message = "something";
    this.manager.invokeMethod(actorId, "say", message.getBytes()).block();
  }

  @Test
  public void activateThenInvoke() throws Exception {
    ActorId actorId = newActorId();
    byte[] message = this.context.getObjectSerializer().serialize("something");
    this.manager.activateActor(actorId).block();
    byte[] response = this.manager.invokeMethod(actorId, "say", message).block();
    Assert.assertEquals(executeSayMethod(
      this.context.getObjectSerializer().deserialize(message, String.class)),
      this.context.getObjectSerializer().deserialize(response, String.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void activateInvokeDeactivateThenInvoke() throws Exception {
    ActorId actorId = newActorId();
    byte[] message = this.context.getObjectSerializer().serialize("something");
    this.manager.activateActor(actorId).block();
    byte[] response = this.manager.invokeMethod(actorId, "say", message).block();
    Assert.assertEquals(executeSayMethod(
      this.context.getObjectSerializer().deserialize(message, String.class)),
      this.context.getObjectSerializer().deserialize(response, String.class));

    this.manager.deactivateActor(actorId).block();
    this.manager.invokeMethod(actorId, "say", message).block();
  }

  @Test
  public void invokeReminderNotRemindable() throws Exception {
    ActorId actorId = newActorId();
    ActorRuntimeContext<NotRemindableActor> context = createContext(NotRemindableActor.class);
    ActorManager<NotRemindableActor> manager = new ActorManager<>(context);
    manager.invokeReminder(actorId, "myremind", createReminderParams("hello")).block();
  }

  @Test(expected = IllegalArgumentException.class)
  public void invokeReminderBeforeActivate() throws Exception {
    ActorId actorId = newActorId();
    this.manager.invokeReminder(actorId, "myremind", createReminderParams("hello")).block();
  }

  @Test
  public void activateThenInvokeReminder() throws Exception {
    ActorId actorId = newActorId();
    this.manager.activateActor(actorId).block();
    this.manager.invokeReminder(actorId, "myremind", createReminderParams("hello")).block();
  }

  @Test(expected = IllegalArgumentException.class)
  public void activateDeactivateThenInvokeReminder() throws Exception {
    ActorId actorId = newActorId();
    this.manager.activateActor(actorId).block();
    this.manager.deactivateActor(actorId).block();;
    this.manager.invokeReminder(actorId, "myremind", createReminderParams("hello")).block();
  }

  @Test(expected = IllegalArgumentException.class)
  public void invokeTimerBeforeActivate() {
    ActorId actorId = newActorId();
    this.manager.invokeTimer(actorId, "count").block();
  }

  @Test(expected = IllegalStateException.class)
  public void activateThenInvokeTimerBeforeRegister() {
    ActorId actorId = newActorId();
    this.manager.activateActor(actorId).block();
    this.manager.invokeTimer(actorId, "unknown").block();
  }

  @Test
  public void activateThenInvokeTimer() {
    ActorId actorId = newActorId();
    this.manager.activateActor(actorId).block();
    this.manager.invokeTimer(actorId, "count").block();
    byte[] response = this.manager.invokeMethod(actorId, "getCount", null).block();
    Assert.assertEquals("2", new String(response));
  }

  @Test(expected = IllegalArgumentException.class)
  public void activateInvokeTimerDeactivateThenInvokeTimer() {
    ActorId actorId = newActorId();
    this.manager.activateActor(actorId).block();
    this.manager.invokeTimer(actorId, "count").block();
    byte[] response = this.manager.invokeMethod(actorId, "getCount", null).block();
    Assert.assertEquals("2", new String(response));

    this.manager.deactivateActor(actorId).block();
    this.manager.invokeTimer(actorId, "count").block();
  }

  private byte[] createReminderParams(String data) throws IOException {
    byte[] serializedData = this.context.getObjectSerializer().serialize(data);
    ActorReminderParams params = new ActorReminderParams(serializedData, Duration.ofSeconds(1), Duration.ofSeconds(1));
    return INTERNAL_SERIALIZER.serialize(params);
  }

  private static ActorId newActorId() {
    return new ActorId(Integer.toString(ACTOR_ID_COUNT.incrementAndGet()));
  }

  private static String executeSayMethod(String something) {
    return "Said: " + (something == null ? "" : something);
  }

  private static <T extends AbstractActor> ActorRuntimeContext createContext(Class<T> clazz) {
    DaprClient daprClient = mock(DaprClient.class);

    when(daprClient.registerActorTimer(any(), any(), any(), any())).thenReturn(Mono.empty());
    when(daprClient.registerActorReminder(any(), any(), any(), any())).thenReturn(Mono.empty());
    when(daprClient.unregisterActorTimer(any(), any(), any())).thenReturn(Mono.empty());
    when(daprClient.unregisterActorReminder(any(), any(), any())).thenReturn(Mono.empty());

    return new ActorRuntimeContext(
      mock(ActorRuntime.class),
      new DefaultObjectSerializer(),
      new DefaultActorFactory<T>(),
      ActorTypeInformation.create(clazz),
      daprClient,
      mock(DaprStateAsyncProvider.class)
    );
  }
}

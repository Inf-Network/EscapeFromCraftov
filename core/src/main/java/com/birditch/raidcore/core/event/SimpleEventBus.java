package com.birditch.raidcore.core.event;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.event.EventSubscription;
import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.event.Subscribe;
import com.birditch.raidcore.common.check.Preconditions;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/// [EventBus] implementation dispatching by exact event class: publishing an event
/// reaches only listeners subscribed to `event.getClass()` itself, never supertypes.
///
/// `publish` is main-thread only (domain state is main-thread confined). Listener
/// failures never reach the publisher: each listener runs in its own try/catch and
/// failures are logged at [Level#SEVERE].
///
/// [#register] binds `@Subscribe` methods through
/// [MethodHandles#publicLookup()]`.unreflect` so dispatch avoids reflective
/// `Method.invoke` overhead.
public final class SimpleEventBus implements EventBus {

  private final Logger logger;
  private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Registration>> listeners =
      new ConcurrentHashMap<>();

  public SimpleEventBus(Logger logger) {
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  @Override
  public <T extends RaidEvent> EventSubscription subscribe(Class<T> eventType, Consumer<T> listener) {
    Preconditions.checkNotNull(eventType, "eventType");
    Preconditions.checkNotNull(listener, "listener");
    return addRegistration(eventType, event -> listener.accept(eventType.cast(event)));
  }

  @Override
  public EventSubscription register(Object listener) {
    Preconditions.checkNotNull(listener, "listener");
    List<SubscriberMethod> methods = scanSubscriberMethods(listener);
    if (methods.isEmpty()) {
      throw new IllegalArgumentException(
          "No public @Subscribe methods found on " + listener.getClass().getName());
    }
    List<EventSubscription> subscriptions = new ArrayList<>(methods.size());
    for (SubscriberMethod method : methods) {
      subscriptions.add(addRegistration(method.eventType(), method.invoker()));
    }
    return new CompositeSubscription(List.copyOf(subscriptions));
  }

  @Override
  public void publish(RaidEvent event) {
    Preconditions.checkNotNull(event, "event");
    List<Registration> registrations = listeners.get(event.getClass());
    if (registrations == null) {
      return;
    }
    for (Registration registration : registrations) {
      try {
        registration.invoker.invoke(event);
      } catch (Throwable e) {
        logger.log(Level.SEVERE, "Event listener failed for " + event.getClass().getName(), e);
      }
    }
  }

  private Registration addRegistration(Class<?> eventType, EventInvoker invoker) {
    Registration registration = new Registration(eventType, invoker);
    listeners.computeIfAbsent(eventType, _ -> new CopyOnWriteArrayList<>()).add(registration);
    return registration;
  }

  /// Validates and binds every `@Subscribe` method before touching bus state, so an
  /// invalid listener never leaves partial registrations behind.
  private List<SubscriberMethod> scanSubscriberMethods(Object listener) {
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    List<SubscriberMethod> methods = new ArrayList<>();
    for (Method method : listener.getClass().getMethods()) {
      if (method.isSynthetic() || !method.isAnnotationPresent(Subscribe.class)) {
        continue;
      }
      methods.add(new SubscriberMethod(subscribedEventType(method), bind(lookup, method, listener)));
    }
    return methods;
  }

  private static Class<? extends RaidEvent> subscribedEventType(Method method) {
    if (Modifier.isStatic(method.getModifiers())) {
      throw new IllegalArgumentException(
          "@Subscribe method " + describe(method) + " must not be static");
    }
    Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length != 1) {
      throw new IllegalArgumentException("@Subscribe method " + describe(method)
          + " must take exactly one parameter, found " + parameters.length);
    }
    Class<?> parameter = parameters[0];
    if (!RaidEvent.class.isAssignableFrom(parameter)) {
      throw new IllegalArgumentException("@Subscribe method " + describe(method)
          + " parameter must be a RaidEvent subtype, found " + parameter.getName());
    }
    return parameter.asSubclass(RaidEvent.class);
  }

  private static EventInvoker bind(MethodHandles.Lookup lookup, Method method, Object listener) {
    MethodHandle handle;
    try {
      handle = lookup.unreflect(method).bindTo(listener);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException("@Subscribe method " + describe(method)
          + " is not accessible via MethodHandles.publicLookup(); "
          + "declare it public on a public class", e);
    }
    return handle::invoke;
  }

  private static String describe(Method method) {
    return method.getDeclaringClass().getName() + "#" + method.getName();
  }

  @FunctionalInterface
  private interface EventInvoker {
    void invoke(RaidEvent event) throws Throwable;
  }

  private record SubscriberMethod(Class<? extends RaidEvent> eventType, EventInvoker invoker) {}

  private final class Registration implements EventSubscription {

    private final Class<?> eventType;
    private final EventInvoker invoker;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private Registration(Class<?> eventType, EventInvoker invoker) {
      this.eventType = eventType;
      this.invoker = invoker;
    }

    @Override
    public void unsubscribe() {
      if (active.compareAndSet(true, false)) {
        List<Registration> registrations = listeners.get(eventType);
        if (registrations != null) {
          registrations.remove(this);
        }
      }
    }

    @Override
    public boolean active() {
      return active.get();
    }
  }

  private record CompositeSubscription(List<EventSubscription> subscriptions)
      implements EventSubscription {

    @Override
    public void unsubscribe() {
      subscriptions.forEach(EventSubscription::unsubscribe);
    }

    @Override
    public boolean active() {
      return subscriptions.stream().anyMatch(EventSubscription::active);
    }
  }
}

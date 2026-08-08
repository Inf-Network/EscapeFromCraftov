package com.birditch.raidcore.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.event.EventSubscription;
import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.event.Subscribe;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class SimpleEventBusTest {

  public record AlphaEvent(String tag) implements RaidEvent {}

  public record BetaEvent(int value) implements RaidEvent {}

  public interface ParentEvent extends RaidEvent {}

  public record ChildEvent() implements ParentEvent {}

  private final CapturingHandler logCapture = new CapturingHandler();
  private SimpleEventBus bus;

  @BeforeEach
  void setUp() {
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(logCapture);
    bus = new SimpleEventBus(logger);
  }

  @Nested
  @DisplayName("subscribe and publish")
  class SubscribeAndPublish {

    @Test
    void deliversEventsOfTheExactSubscribedClassOnly() {
      List<AlphaEvent> received = new ArrayList<>();
      bus.subscribe(AlphaEvent.class, received::add);
      bus.publish(new AlphaEvent("one"));
      bus.publish(new BetaEvent(2));
      assertEquals(List.of(new AlphaEvent("one")), received);
    }

    @Test
    void doesNotDispatchPolymorphically() {
      List<ParentEvent> viaParent = new ArrayList<>();
      List<RaidEvent> viaMarker = new ArrayList<>();
      bus.subscribe(ParentEvent.class, viaParent::add);
      bus.subscribe(RaidEvent.class, viaMarker::add);
      bus.publish(new ChildEvent());
      assertTrue(viaParent.isEmpty(), "supertype listener must not receive subtype events");
      assertTrue(viaMarker.isEmpty(), "marker-type listener must not receive subtype events");
    }

    @Test
    void publishingWithoutListenersIsANoOp() {
      bus.publish(new AlphaEvent("nobody"));
      assertTrue(logCapture.records().isEmpty());
    }

    @Test
    void listenerExceptionIsIsolatedAndLoggedSevere() {
      List<AlphaEvent> received = new ArrayList<>();
      IllegalStateException boom = new IllegalStateException("listener boom");
      bus.subscribe(AlphaEvent.class, _ -> {
        throw boom;
      });
      bus.subscribe(AlphaEvent.class, received::add);
      AlphaEvent event = new AlphaEvent("survives");
      bus.publish(event);
      assertEquals(List.of(event), received);
      assertTrue(logCapture.hasSevere(boom), "listener failure should be logged SEVERE");
    }

    @Test
    void unsubscribeStopsDeliveryAndIsIdempotent() {
      List<AlphaEvent> received = new ArrayList<>();
      EventSubscription subscription = bus.subscribe(AlphaEvent.class, received::add);
      assertTrue(subscription.active());
      bus.publish(new AlphaEvent("first"));
      subscription.unsubscribe();
      subscription.unsubscribe();
      assertFalse(subscription.active());
      bus.publish(new AlphaEvent("second"));
      assertEquals(List.of(new AlphaEvent("first")), received);
    }

    @Test
    void rejectsNullArguments() {
      assertThrows(NullPointerException.class, () -> bus.subscribe(null, _ -> {}));
      assertThrows(NullPointerException.class, () -> bus.subscribe(AlphaEvent.class, null));
      assertThrows(NullPointerException.class, () -> bus.publish(null));
      assertThrows(NullPointerException.class, () -> bus.register(null));
    }
  }

  @Nested
  @DisplayName("register(Object)")
  class RegisterListener {

    @Test
    void subscribesEveryPublicSubscribeMethod() {
      MultiListener listener = new MultiListener();
      EventSubscription subscription = bus.register(listener);
      assertNotNull(subscription);
      assertTrue(subscription.active());
      bus.publish(new AlphaEvent("a"));
      bus.publish(new BetaEvent(7));
      assertEquals(List.of(new AlphaEvent("a"), new BetaEvent(7)), listener.received);
    }

    @Test
    void returnedSubscriptionUnsubscribesAllMethodsAtOnce() {
      MultiListener listener = new MultiListener();
      EventSubscription subscription = bus.register(listener);
      subscription.unsubscribe();
      assertFalse(subscription.active());
      bus.publish(new AlphaEvent("a"));
      bus.publish(new BetaEvent(7));
      assertTrue(listener.received.isEmpty());
    }

    @Test
    void rejectsListenerWithoutSubscribeMethods() {
      Object listener = new Object();
      assertThrows(IllegalArgumentException.class, () -> bus.register(listener));
    }

    @Test
    void rejectsSubscribeMethodWithWrongParameterCount() {
      TwoParameterListener listener = new TwoParameterListener();
      assertThrows(IllegalArgumentException.class, () -> bus.register(listener));
    }

    @Test
    void rejectsSubscribeMethodWithNonEventParameter() {
      WrongTypeListener listener = new WrongTypeListener();
      assertThrows(IllegalArgumentException.class, () -> bus.register(listener));
    }

    @Test
    void rejectsStaticSubscribeMethod() {
      StaticMethodListener listener = new StaticMethodListener();
      assertThrows(IllegalArgumentException.class, () -> bus.register(listener));
    }

    @Test
    void invalidListenerLeavesNoPartialRegistrations() {
      PartiallyValidListener listener = new PartiallyValidListener();
      assertThrows(IllegalArgumentException.class, () -> bus.register(listener));
      bus.publish(new AlphaEvent("orphan"));
      assertTrue(listener.received.isEmpty(), "valid method of a rejected listener must not fire");
    }
  }

  public static final class MultiListener {

    final List<RaidEvent> received = new ArrayList<>();

    @Subscribe
    public void onAlpha(AlphaEvent event) {
      received.add(event);
    }

    @Subscribe
    public void onBeta(BetaEvent event) {
      received.add(event);
    }
  }

  public static final class TwoParameterListener {

    final List<RaidEvent> received = new ArrayList<>();

    @Subscribe
    public void onAlpha(AlphaEvent event, String extra) {
      received.add(new AlphaEvent(event.tag() + extra));
    }
  }

  public static final class WrongTypeListener {

    final List<String> received = new ArrayList<>();

    @Subscribe
    public void onText(String text) {
      received.add(text);
    }
  }

  public static final class StaticMethodListener {

    static final List<RaidEvent> RECEIVED = new ArrayList<>();

    @Subscribe
    public static void onAlpha(AlphaEvent event) {
      RECEIVED.add(event);
    }
  }

  public static final class PartiallyValidListener {

    final List<RaidEvent> received = new ArrayList<>();

    @Subscribe
    public void onAlpha(AlphaEvent event) {
      received.add(event);
    }

    @Subscribe
    public void onText(String text) {
      received.add(new AlphaEvent(text));
    }
  }

  private static final class CapturingHandler extends Handler {

    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    List<LogRecord> records() {
      return List.copyOf(records);
    }

    boolean hasSevere(Throwable thrown) {
      return records.stream().anyMatch(record ->
          record.getLevel() == Level.SEVERE && record.getThrown() == thrown);
    }
  }
}

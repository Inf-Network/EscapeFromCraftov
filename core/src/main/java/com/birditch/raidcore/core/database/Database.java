package com.birditch.raidcore.core.database;

import java.util.function.Function;
import org.hibernate.Session;

/// Shared Hibernate ORM transaction boundary. Every callback receives a
/// short-lived Session with an active transaction and must not retain it.
public interface Database extends AutoCloseable {

  <T> T transaction(Function<Session, T> work);

  @Override
  void close();
}

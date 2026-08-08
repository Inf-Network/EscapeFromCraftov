package com.birditch.raidcore.core.database;

import java.sql.Connection;
import java.sql.SQLException;

/// Source of pooled JDBC connections. Callers must close the returned connection
/// (returning it to the pool); only repository IO on the injected executor may use it.
public interface Database {

  Connection connection() throws SQLException;

  void close();
}

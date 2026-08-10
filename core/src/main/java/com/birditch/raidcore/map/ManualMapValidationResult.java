package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

public record ManualMapValidationResult(List<MapValidationIssue> issues, int qualityScore) {

  public ManualMapValidationResult {
    issues = List.copyOf(Preconditions.checkNotNull(issues, "issues"));
    Preconditions.checkArgument(qualityScore >= 0 && qualityScore <= 100,
        "qualityScore must be 0..100");
  }

  public boolean hasErrors() {
    return issues.stream().anyMatch(issue -> issue.severity() == MapValidationSeverity.ERROR);
  }

  public long errorCount() {
    return count(MapValidationSeverity.ERROR);
  }

  public long warningCount() {
    return count(MapValidationSeverity.WARNING);
  }

  private long count(MapValidationSeverity severity) {
    return issues.stream().filter(issue -> issue.severity() == severity).count();
  }
}

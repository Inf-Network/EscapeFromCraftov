package com.birditch.escapefromcraftov.automap;

import java.util.List;

public record MapValidationReport(List<ValidationIssue> issues) {

  public MapValidationReport {
    issues = List.copyOf(issues);
  }

  public boolean hasErrors() {
    return issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.ERROR);
  }
}

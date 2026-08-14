package com.birditch.escapefromcraftov.common.paging;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/// One page of a larger list; `page` is 1-based and `items` is immutable.
public record Page<T>(List<T> items, int page, int pageSize, int totalItems, int totalPages) {

    public Page {
        Preconditions.checkNotNull(items, "items");
        Preconditions.checkArgument(page >= 1, "page must be at least 1");
        Preconditions.checkArgument(pageSize >= 1, "pageSize must be at least 1");
        Preconditions.checkArgument(totalItems >= 0, "totalItems must not be negative");
        Preconditions.checkArgument(totalPages >= 0, "totalPages must not be negative");
        items = List.copyOf(items);
    }

    /// Slices `source` into the requested 1-based page, clamping `page` into
    /// the valid range. An empty source yields page 1 with zero totals.
    public static <T> Page<T> of(List<T> source, int page, int pageSize) {
        Preconditions.checkNotNull(source, "source");
        Preconditions.checkArgument(pageSize >= 1, "pageSize must be at least 1");
        List<T> copy = List.copyOf(source);
        int totalItems = copy.size();
        if (totalItems == 0) {
            return new Page<>(List.of(), 1, pageSize, 0, 0);
        }
        int totalPages = (totalItems + pageSize - 1) / pageSize;
        int currentPage = Math.clamp(page, 1, totalPages);
        int fromIndex = (currentPage - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalItems);
        return new Page<>(copy.subList(fromIndex, toIndex), currentPage, pageSize, totalItems, totalPages);
    }
}

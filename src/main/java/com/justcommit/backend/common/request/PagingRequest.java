package com.justcommit.backend.common.request;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public interface PagingRequest {

    int DEFAULT_PAGE = 0;
    int DEFAULT_SIZE = 20;
    int MAX_SIZE = 50;

    Integer page();

    Integer size();

    default int defaultSize() {
        return DEFAULT_SIZE;
    }

    default int maxSize() {
        return MAX_SIZE;
    }

    default Sort defaultSort() {
        return Sort.unsorted();
    }

    default Pageable toPageable() {
        int pageNumber = (page() == null || page() < 0) ? DEFAULT_PAGE : page();
        int pageSize = (size() == null || size() < 1) ? defaultSize() : Math.min(size(), maxSize());
        return PageRequest.of(pageNumber, pageSize, defaultSort());
    }
}

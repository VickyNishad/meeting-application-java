package com.example.meetplatform.common.response;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Clamps client-supplied paging parameters so a request can never load an unbounded page. */
public final class PageParams {

    public static final int MAX_SIZE = 100;

    private PageParams() {
    }

    public static PageRequest of(Integer page, Integer size, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);
        return PageRequest.of(p, s, sort);
    }
}

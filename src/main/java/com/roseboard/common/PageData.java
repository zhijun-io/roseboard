package com.roseboard.common;

import java.util.List;

public record PageData<T>(
        List<T> data,
        long totalPages,
        long totalElements,
        boolean hasNext,
        boolean hasPrevious
) {
    public PageData(List<T> data, long pageSize, long page, long totalElements) {
        this(data,
                pageSize <= 0 ? 0 : Math.toIntExact(Math.ceilDiv(totalElements, pageSize)),
                totalElements,
                pageSize > 0 && page + 1 < Math.ceilDiv(totalElements, pageSize),
                pageSize > 0 && page > 0);
    }

}

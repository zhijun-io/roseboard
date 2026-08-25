package com.roseboard.infrastructure.websocket.cmd.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsPageData<T> {
    private List<T> data;
    private int totalPages;
    private long totalElements;
    private boolean hasNext;

    public WsPageData() {
    }

    public WsPageData(List<T> data, int totalPages, long totalElements, boolean hasNext) {
        this.data = data;
        this.totalPages = totalPages;
        this.totalElements = totalElements;
        this.hasNext = hasNext;
    }

    public static <T> WsPageData<T> single(T item) {
        return new WsPageData<>(List.of(item), 1, 1, false);
    }

    public static <T> WsPageData<T> empty() {
        return new WsPageData<>(Collections.emptyList(), 0, 0, false);
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }
}

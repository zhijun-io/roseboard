package com.roseboard.infrastructure.transport;

import com.roseboard.infrastructure.queue.spi.QueueCallback;

public interface TransportServiceCallback<T> {

    TransportServiceCallback<Void> EMPTY = new TransportServiceCallback<>() {
        @Override
        public void onSuccess(Void msg) {
        }

        @Override
        public void onError(Throwable e) {
        }
    };

    void onSuccess(T msg);

    void onError(Throwable e);

    static QueueCallback toQueueCallback(TransportServiceCallback<Void> callback) {
        if (callback == null || callback == EMPTY) {
            return QueueCallback.EMPTY;
        }
        return new QueueCallback() {
            @Override
            public void onSuccess() {
                callback.onSuccess(null);
            }

            @Override
            public void onFailure(Throwable t) {
                callback.onError(t);
            }
        };
    }
}

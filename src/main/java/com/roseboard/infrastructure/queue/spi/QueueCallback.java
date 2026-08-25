/*
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.roseboard.infrastructure.queue.spi;

import java.util.concurrent.atomic.AtomicBoolean;

public interface QueueCallback {
    QueueCallback EMPTY = new QueueCallback() {
        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(Throwable t) {
        }
    };

    void onSuccess();

    void onFailure(Throwable t);

    static QueueCallback once(QueueCallback delegate) {
        QueueCallback target = delegate == null ? EMPTY : delegate;
        AtomicBoolean completed = new AtomicBoolean(false);
        return new QueueCallback() {
            @Override
            public void onSuccess() {
                if (completed.compareAndSet(false, true)) {
                    target.onSuccess();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                if (completed.compareAndSet(false, true)) {
                    target.onFailure(t);
                }
            }
        };
    }
}

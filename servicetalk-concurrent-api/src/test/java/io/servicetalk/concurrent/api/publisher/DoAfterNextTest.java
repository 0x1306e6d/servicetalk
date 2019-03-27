/*
 * Copyright © 2018-2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api.publisher;

import io.servicetalk.concurrent.api.Publisher;

import org.junit.Test;

import java.util.function.Consumer;

import static io.servicetalk.concurrent.api.SourceAdapters.toSource;
import static io.servicetalk.concurrent.internal.DeliberateException.DELIBERATE_EXCEPTION;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;

public class DoAfterNextTest extends AbstractDoNextTest {
    @Override
    protected <T> Publisher<T> doNext(Publisher<T> publisher, Consumer<T> consumer) {
        return publisher.doAfterOnNext(consumer);
    }

    @Override
    @Test
    public void testCallbackThrowsError() {
        toSource(doNext(Publisher.from("Hello"), s1 -> {
            throw DELIBERATE_EXCEPTION;
        })).subscribe(subscriber);
        subscriber.request(1);
        assertThat(subscriber.takeItems(), contains("Hello"));
        assertThat(subscriber.takeError(), sameInstance(DELIBERATE_EXCEPTION));
    }
}

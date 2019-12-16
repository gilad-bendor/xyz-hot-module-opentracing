/* Copyright 2019 The OpenTracing Authors
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

package io.opentracing.contrib.specialagent.test.spymemcached;

import io.opentracing.contrib.specialagent.TestUtil;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import net.spy.memcached.MemcachedClient;

public class SpymemcachedITest {
  public static void main(final String[] args) throws Exception {
    TestUtil.initTerminalExceptionHandler();
    MemcachedClient client = new MemcachedClient(new InetSocketAddress("localhost", 11211));
    final Boolean op = client.set("key", 120, "value").get(15, TimeUnit.SECONDS);
    if (!op)
      throw new AssertionError("ERROR: failed to set key/value");

    if (!"value".equals(client.get("key")))
      throw new AssertionError("ERROR: failed to get key value");

    client.shutdown();
    TestUtil.checkSpan("java-memcached", 2);
  }
}
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.nativo.jlibaio;

import static org.jctools.util.UnsafeAccess.UNSAFE;

/**
 * Unsafe-based access representation for:
 * <pre>
 * struct aio_ring {
 *    unsigned	id;
 *    unsigned nr;
 *    unsigned head;
 *    unsigned tail;
 *    unsigned magic;
 *    unsigned compat_features;
 *    unsigned incompat_features;
 *    unsigned header_length;
 *
 *    struct io_event io_events[0];
 * };
 * </pre>
 */
public final class AioRing {

   private static final int AIO_RING_MAGIC = 0xa10a10a1;
   private static final int AIO_RING_INCOMPAT_FEATURES = 0;
   private static final int ID_OFFSET = 0;
   private static final int NR_OFFSET = ID_OFFSET + Integer.BYTES;
   private static final int HEAD_OFFSET = NR_OFFSET + Integer.BYTES;
   private static final int TAIL_OFFSET = HEAD_OFFSET + Integer.BYTES;
   private static final int MAGIC_OFFSET = TAIL_OFFSET + Integer.BYTES;
   private static final int COMPAT_FEATURES_OFFSET = MAGIC_OFFSET + Integer.BYTES;
   private static final int INCOMPAT_FEATURES_OFFSET = COMPAT_FEATURES_OFFSET + Integer.BYTES;
   private static final int HEADER_LENGTH_OFFSET = INCOMPAT_FEATURES_OFFSET + Integer.BYTES;
   private static final int IO_EVENT_OFFSET = HEADER_LENGTH_OFFSET + Integer.BYTES;
   public static final int SIZE_OF_AIO_RING = IO_EVENT_OFFSET + Long.BYTES;
   /**
    * <pre>
    * struct io_event {
    *    __u64		data;
    *    __u64 obj;
    *    __s64 res;
    *    __s64 res2;
    * };
    * </pre>
    */
   private static final int SIZE_OF_IO_EVENT_STRUCT = 32;
   private final long nrAddress;
   private final long headAddress;
   private final long tailAddress;
   private final long ioEventsAddress;

   public AioRing(long aioRingAddress) {
      if (!hasUsableRing(aioRingAddress)) {
         throw new IllegalStateException("Unsafe kernel bypass cannot be used!");
      }
      this.nrAddress = aioRingAddress + NR_OFFSET;
      this.headAddress = aioRingAddress + HEAD_OFFSET;
      this.tailAddress = aioRingAddress + TAIL_OFFSET;
      this.ioEventsAddress = aioRingAddress + IO_EVENT_OFFSET;
   }

   public static boolean hasUsableRing(long aioRingAddress) {
      return UNSAFE.getInt(aioRingAddress + MAGIC_OFFSET) == AIO_RING_MAGIC &&
         UNSAFE.getInt(aioRingAddress + INCOMPAT_FEATURES_OFFSET) == AIO_RING_INCOMPAT_FEATURES;
   }

   public int size() {
      final long nrAddress = this.nrAddress;
      final long headAddress = this.headAddress;
      final long tailAddress = this.tailAddress;
      final int nr = UNSAFE.getInt(nrAddress);
      final int head = UNSAFE.getInt(headAddress);
      // no need of membar here because Unsafe::getInt already provide it
      final int tail = UNSAFE.getIntVolatile(null, tailAddress);
      int available = tail - head;
      if (available < 0) {
         available += nr;
      }
      // this is to mitigate a RHEL BUG: see native code for more info
      if (available > nr) {
         return 0;
      }
      return available;
   }

   public int poll(long completedIoEvents, int min, int max) {
      final long nrAddress = this.nrAddress;
      final long headAddress = this.headAddress;
      final long tailAddress = this.tailAddress;
      final int nr = UNSAFE.getInt(nrAddress);
      int head = UNSAFE.getInt(headAddress);
      // no need of membar here because Unsafe::getInt already provide it
      final int tail = UNSAFE.getIntVolatile(null, tailAddress);
      int available = tail - head;
      if (available < 0) {
         // a wrap has occurred
         available += nr;
      }
      if (available < min) {
         return 0;
      }
      if (available == 0) {
         return 0;
      }
      // this is to mitigate a RHEL BUG: see native code for more info
      if (available > nr) {
         return -1;
      }
      available = Math.min(available, max);
      final long ringIoEvents = this.ioEventsAddress;
      final long startRingAioEvents = ringIoEvents + (head * SIZE_OF_IO_EVENT_STRUCT);
      head += available;
      final long availableBytes = available * SIZE_OF_IO_EVENT_STRUCT;
      if (head < nr) {
         // no wrap: JDK should account to save long safepoint pauses
         UNSAFE.copyMemory(startRingAioEvents, completedIoEvents, availableBytes);
      } else {
         head -= nr;
         // copy trail
         final long trailBytes = (available - head) * SIZE_OF_IO_EVENT_STRUCT;
         UNSAFE.copyMemory(startRingAioEvents, completedIoEvents, trailBytes);
         completedIoEvents += trailBytes;
         final long headerBytes = availableBytes - trailBytes;
         UNSAFE.copyMemory(ringIoEvents, completedIoEvents, headerBytes);
         // copy header
      }
      assert head >= 0 && head <= nr;
      // it allow the kernel to build its own view of the ring buffer size
      // and push new events if there are any
      UNSAFE.putOrderedInt(null, headAddress, head);
      return available;
   }

}

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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Unsafe access representation for an array of:
 * <pre>
 * struct io_event {
 *    __u64		data;		// the data field from the iocb
* 	   __u64		obj;		// what iocb this event came from
 *    __s64		res;	   // result code for this event
 *    __s64		res2;		// secondary result
 * };
 * </pre>
 * <p>
 * Support is just for 64 bits for now ie sizeof(unsigned) == Integer.BYTES
 */
public final class IoEventArray {

   // TODO use JNI to obtain this
   public static final int SIZE_OF_IO_EVENT_STRUCT = 32;
   private ByteBuffer array;
   private final long arrayAddress;
   private final IoEvent ioEvent;
   private final int capacity;

   public IoEventArray(int capacity) {
      array = ByteBuffer.allocateDirect(capacity * SIZE_OF_IO_EVENT_STRUCT).order(ByteOrder.nativeOrder());
      arrayAddress = LibaioContext.RuntimeDependent.directBufferAddress(array);
      ioEvent = new IoEvent();
      this.capacity = capacity;
   }

   public long address() {
      return arrayAddress;
   }

   // Flyweight approach: this could use Unsafe too ;)
   public final class IoEvent {

      static final int DATA_OFFSET = 0;
      static final int OBJ_OFFSET = DATA_OFFSET + 8;
      static final int RES_OFFSET = OBJ_OFFSET + 8;
      static final int RES2_OFFSET = RES_OFFSET + 8;
      private int offset;

      private IoEvent() {
         offset = -1;
      }

      public long data() {
         return array.getLong(offset + DATA_OFFSET);
      }

      public IoEvent data(long value) {
         array.putLong(offset + DATA_OFFSET, value);
         return this;
      }

      public long obj() {
         return array.getLong(offset + OBJ_OFFSET);
      }

      public IoEvent obj(long value) {
         array.putLong(offset + OBJ_OFFSET, value);
         return this;
      }

      public long res() {
         return array.getLong(offset + RES_OFFSET);
      }

      public IoEvent res(long value) {
         array.putLong(offset + RES_OFFSET, value);
         return this;
      }

   }

   public IoEvent get(int index) {
      assert index >= 0 && index < capacity;
      final int offset = index * SIZE_OF_IO_EVENT_STRUCT;
      ioEvent.offset = offset;
      return ioEvent;
   }

   public void close() {
      this.array = null;
   }

}

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
 * <pre>
 * struct iocb {
 *    __u64   aio_data;
 *    __u32   PADDED(aio_key, aio_rw_flags);
 *    __u16   aio_lio_opcode;
 *    __s16   aio_reqprio;
 *    __u32   aio_fildes;
 *    __u64   aio_buf;
 *    __u64   aio_nbytes;
 *    __s64   aio_offset;
 *    __u64   aio_reserved2;
 *    __u32   aio_flags;
 *    __u32   aio_resfd;
 * };
 * </pre>
 */
public final class IoCb {

   private IoCb() {

   }

   /**
    * Supported aio_lio_opcode
    */
   static final short IOCB_CMD_PREAD = 0;
   static final short IOCB_CMD_PWRITE = 1;
   static final short IOCB_CMD_FDSYNC = 3;

   private static final int DATA_OFFSET = 0;
   private static final int PADDED_OFFSET = DATA_OFFSET + 8;
   private static final int LIO_OPCODE_OFFSET = PADDED_OFFSET + 8;
   private static final int REQ_PRIO_OFFSET = LIO_OPCODE_OFFSET + 2;
   private static final int FILDES_OFFSET = REQ_PRIO_OFFSET + 2;
   private static final int BUF_OFFSET = FILDES_OFFSET + 4;
   private static final int N_BYTES = BUF_OFFSET + 8;
   private static final int OFFSET_OFFSET = N_BYTES + 8;
   private static final int RESERVED_OFFSET = OFFSET_OFFSET + 8;
   private static final int FLAGS_OFFSET = RESERVED_OFFSET + 8;
   private static final int REST_FD_OFFSET = FLAGS_OFFSET + 4;
   public static final int SIZE_OF_IOCB_STRUCT = REST_FD_OFFSET + 4;

   public static int aioFildes(ByteBuffer byteBuffer) {
      return byteBuffer.getInt(FILDES_OFFSET);
   }

   public static long aioData(ByteBuffer byteBuffer) {
      return byteBuffer.getLong(DATA_OFFSET);
   }

   public static short lioOpCode(ByteBuffer byteBuffer) {
      return byteBuffer.getShort(LIO_OPCODE_OFFSET);
   }

   public static final int SIZE_OF() {
      return SIZE_OF_IOCB_STRUCT;
   }

   public static final class Array {

      private final ByteBuffer buffer;

      public Array(int capacity) {
         this.buffer = ByteBuffer.allocateDirect(capacity * SIZE_OF_IOCB_STRUCT).order(ByteOrder.nativeOrder());
      }

      public ByteBuffer sliceOf(int index) {
         final int start = index * SIZE_OF_IOCB_STRUCT;
         buffer.clear().position(start).limit(start + SIZE_OF_IOCB_STRUCT);
         return buffer.slice().order(ByteOrder.nativeOrder());
      }
   }

}

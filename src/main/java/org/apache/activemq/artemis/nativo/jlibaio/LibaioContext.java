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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.jctools.queues.MpmcArrayQueue;
import org.jctools.queues.atomic.MpmcAtomicArrayQueue;
import org.jctools.util.UnsafeAccess;
import sun.misc.Unsafe;

/**
 * This class is used as an aggregator for the {@link LibaioFile}.
 * <br>
 * It holds native data, and it will share a libaio queue that can be used by multiple files.
 * <br>
 * You need to use the poll methods to read the result of write and read submissions.
 * <br>
 * You also need to use the special buffer created by {@link LibaioFile} as you need special alignments
 * when dealing with O_DIRECT files.
 * <br>
 * A Single controller can server multiple files. There's no need to create one controller per file.
 * <br>
 * <a href="https://ext4.wiki.kernel.org/index.php/Clarifying_Direct_IO's_Semantics">Interesting reading for this.</a>
 */
public class LibaioContext<Callback extends SubmitInfo> implements Closeable {

   private static final AtomicLong totalMaxIO = new AtomicLong(0);

   /**
    * The Native layer will look at this version.
    */
   private static final int EXPECTED_NATIVE_VERSION = 11;

   private static boolean loaded = false;

   private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

   private static final AtomicInteger contexts = new AtomicInteger(0);

   private static final String FORCE_SYSCALL_PROPERTY_NAME = "org.apache.activemq.artemis.native.jlibaio.FORCE_SYSCALL";

   private static final class PooledIOCB {

      final int id;
      final ByteBuffer bytes;
      final long address;
      SubmitInfo submitInfo;

      PooledIOCB(int id, ByteBuffer iocbBytes) {
         this.id = id;
         assert iocbBytes.capacity() == IoCb.SIZE_OF();
         assert iocbBytes.order() == ByteOrder.nativeOrder();
         this.bytes = iocbBytes;
         this.address = RuntimeDependent.directBufferAddress(bytes);
         this.submitInfo = null;
      }
   }

   public static boolean isLoaded() {
      return loaded;
   }

   private static boolean loadLibrary(final String name) {
      try {
         System.loadLibrary(name);
         if (getNativeVersion() != EXPECTED_NATIVE_VERSION) {
            NativeLogger.LOGGER.incompatibleNativeLibrary();
            return false;
         } else {
            return true;
         }
      } catch (Throwable e) {
         NativeLogger.LOGGER.debug(name + " -> error loading the native library", e);
         return false;
      }

   }

   static {
      String[] libraries = new String[]{"artemis-native-64", "artemis-native-32"};
      for (String library : libraries) {
         if (loadLibrary(library)) {
            loaded = true;
            if (System.getProperty(FORCE_SYSCALL_PROPERTY_NAME) != null) {
               LibaioContext.setForceSyscall(true);
            }
            Runtime.getRuntime().addShutdownHook(new Thread() {
               @Override
               public void run() {
                  shuttingDown.set(true);
                  checkShutdown();
               }
            });
            break;
         } else {
            NativeLogger.LOGGER.debug("Library " + library + " not found!");
         }
      }
      if (!loaded) {
         NativeLogger.LOGGER.debug("Couldn't locate LibAIO Wrapper");
      }
   }

   private static void checkShutdown() {
      if (contexts.get() == 0 && shuttingDown.get()) {
         shutdownHook();
      }
   }

   private static native void shutdownHook();

   public static native void setForceSyscall(boolean value);

   /** The system may choose to set this if a failing condition happened inside the code. */
   public static native boolean isForceSyscall();

   /**
    * This is used to validate leaks on tests.
    *
    * @return the number of allocated aio, to be used on test checks.
    */
   public static long getTotalMaxIO() {
      return totalMaxIO.get();
   }

   /**
    * It will reset all the positions on the buffer to 0, using memset.
    *
    * @param buffer a native buffer.
    *               s
    */
   public void memsetBuffer(ByteBuffer buffer) {
      memsetBuffer(buffer, buffer.limit());
   }

   /**
    * This is used on tests validating for leaks.
    */
   public static void resetMaxAIO() {
      totalMaxIO.set(0);
   }

   /**
    * the native ioContext including the structure created.
    */
   private final ByteBuffer ioControl;
   private final long ioContextAddress;
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private final Semaphore ioSpace;
   private final int queueSize;
   private final boolean useFdatasync;
   private final Queue<PooledIOCB> iocbPool;
   private final PooledIOCB[] iocbArray;
   private final IoEventArray ioEventArray;
   private final AioRing aioRing;
   private final int dumbFD;
   private final ReentrantLock pollLock;

   /**
    * The queue size here will use resources defined on the kernel parameter
    * <a href="https://www.kernel.org/doc/Documentation/sysctl/fs.txt">fs.aio-max-nr</a> .
    *
    * @param queueSize    the size to be initialize on libaio
    *                     io_queue_init which can't be higher than /proc/sys/fs/aio-max-nr.
    * @param useSemaphore should block on a semaphore avoiding using more submits than what's available.
    * @param useFdatasync should use fdatasync before calling callbacks.
    */
   public LibaioContext(int queueSize, boolean useSemaphore, boolean useFdatasync) {
      try {
         contexts.incrementAndGet();
         this.ioControl = newContext(queueSize);
         // Better use JNI here, because the context address size depends on the machine word size
         this.ioContextAddress = getIOContextAddress(ioControl);
         this.iocbPool = RuntimeDependent.newMpmcQueue(queueSize);
         this.iocbArray = new PooledIOCB[queueSize];
         final IoCb.Array arrayOfIocb = new IoCb.Array(queueSize);
         for (int i = 0; i < queueSize; i++) {
            final PooledIOCB pooledIOCB = new PooledIOCB(i, arrayOfIocb.sliceOf(i));
            this.iocbArray[i] = pooledIOCB;
            this.iocbPool.add(pooledIOCB);
         }
         this.ioEventArray = new IoEventArray(queueSize);
         this.useFdatasync = useFdatasync;
         this.aioRing = createAioRing();
         this.dumbFD = dumbFD();
         this.pollLock = new ReentrantLock();
      } catch (Exception e) {
         throw e;
      }
      this.queueSize = queueSize;
      totalMaxIO.addAndGet(queueSize);
      if (useSemaphore) {
         this.ioSpace = new Semaphore(queueSize);
      } else {
         this.ioSpace = null;
      }
   }

   private AioRing createAioRing() {
      if (isForceSyscall()) {
         return null;
      }
      if (!RuntimeDependent.HAS_UNSAFE) {
         return null;
      }
      if (!AioRing.hasUsableRing(ioContextAddress)) {
         return null;
      }
      return new AioRing(ioContextAddress);
   }

   public int submitWrite(int fd, long position, int size, ByteBuffer bufferWrite, Callback callback) throws IOException {
      if (closed.get()) {
         throw new IOException("Libaio Context is closed!");
      }
      try {
         if (ioSpace != null) {
            ioSpace.acquire();
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException(e.getMessage(), e);
      }
      PooledIOCB iocb = iocbPool.poll();
      if (iocb == null) {
         assert ioSpace == null;
         throw new IOException("Not enough space in libaio queue");
      }
      try {
         assert iocb.submitInfo == null;
         // set submitted *before* submitWrite in order to guarantee safe publication thanks to JNI
         iocb.submitInfo = callback;
         submitWrite(fd, ioContextAddress, iocb.address, position, size, RuntimeDependent.directBufferAddress(bufferWrite), iocb.id);
         return iocb.id;
      } catch (IOException ioException) {
         iocb.submitInfo = null;
         iocbPool.add(iocb);
         if (ioSpace != null) {
            ioSpace.release();
         }
         throw ioException;
      }
   }

   public int submitRead(int fd, long position, int size, ByteBuffer bufferWrite, Callback callback) throws IOException {
      if (closed.get()) {
         throw new IOException("Libaio Context is closed!");
      }
      try {
         if (ioSpace != null) {
            ioSpace.acquire();
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException(e.getMessage(), e);
      }
      PooledIOCB iocb = iocbPool.poll();
      if (iocb == null) {
         assert ioSpace == null;
         throw new IOException("Not enough space in libaio queue");
      }
      try {
         assert iocb.submitInfo == null;
         // set submitted *before* submitRead in order to guarantee safe publication thanks to JNI
         iocb.submitInfo = callback;
         submitRead(fd, ioContextAddress, iocb.address, position, size, RuntimeDependent.directBufferAddress(bufferWrite), iocb.id);
         return iocb.id;
      } catch (IOException ioException) {
         iocb.submitInfo = null;
         iocbPool.add(iocb);
         if (ioSpace != null) {
            ioSpace.release();
         }
         throw ioException;
      }
   }

   /**
    * This is used to close the libaio queues and cleanup the native data used.
    * <br>
    * It is unsafe to close the controller while you have pending writes or files open as
    * this could cause core dumps or VM crashes.
    */
   @Override
   public void close() {
      if (!closed.getAndSet(true)) {

         if (ioSpace != null) {
            try {
               ioSpace.tryAcquire(queueSize, 10, TimeUnit.SECONDS);
            } catch (Exception e) {
               NativeLogger.LOGGER.error(e);
            }
         }
         totalMaxIO.addAndGet(-queueSize);

         if (ioControl != null) {
            // submit a dumbFD write
            PooledIOCB iocb = iocbPool.poll();
            if (iocb == null) {
               throw new IllegalStateException("Not enough space in libaio queue to stop the context");
            }
            try {
               // Submitting a dumb write so the loop finishes
               submitWrite(dumbFD, ioContextAddress, iocb.address, 0, 0, 0, iocb.id);
            } catch (IOException ioException) {
               // TODO handle this
            }
            // await until there are no more pending I/O or the blocked poll has completed
            do {
               pollLock.lock();
               try {
                  // poll is locked, will await any blocked pool to complete
                  if (unsafePoll(null, 0, 1) == 0) {
                     Thread.yield();
                  }
               } finally {
                  pollLock.unlock();
               }
            } while (iocbPool.size() < queueSize);
            deleteContext(ioControl);
            pollLock.lock();
            try {
               if (iocb.bytes == null) {
                  throw new NullPointerException("THIS IS IMPOSSIBLE: IT'S JUST TO CREATE AN REACHABILITY FENCE");
               }
               assert iocbPool.size() == queueSize;
               iocbPool.clear();
               Arrays.fill(iocbArray, null);
               ioEventArray.close();
            } finally {
               pollLock.unlock();
            }

         }
         contexts.decrementAndGet();
         checkShutdown();
      }
   }

   @Override
   protected void finalize() throws Throwable {
      super.finalize();
      close();
   }

   /**
    * It will open a file. If you set the direct flag = false then you won't need to use the special buffer.
    * Notice: This will create an empty file if the file doesn't already exist.
    *
    * @param file   the file to be open.
    * @param direct will set ODIRECT.
    * @return It will return a LibaioFile instance.
    * @throws IOException in case of error.
    */
   public LibaioFile<Callback> openFile(File file, boolean direct) throws IOException {
      return openFile(file.getPath(), direct);
   }

   /**
    * It will open a file. If you set the direct flag = false then you won't need to use the special buffer.
    * Notice: This will create an empty file if the file doesn't already exist.
    *
    * @param file   the file to be open.
    * @param direct should use O_DIRECT when opening the file.
    * @return a new open file.
    * @throws IOException in case of error.
    */
   public LibaioFile<Callback> openFile(String file, boolean direct) throws IOException {
      checkNotNull(file, "path");
      checkNotNull(ioControl, "IOContext");

      // note: the native layer will throw an IOException in case of errors
      int res = LibaioContext.open(file, direct);

      return new LibaioFile<>(res, this);
   }

   /**
    * It will open a file disassociated with any sort of factory.
    * This is useful when you won't use reading / writing through libaio like locking files.
    *
    * @param file   a file name
    * @param direct will use O_DIRECT
    * @return a new file
    * @throws IOException in case of error.
    */
   public static LibaioFile openControlFile(String file, boolean direct) throws IOException {
      checkNotNull(file, "path");

      // note: the native layer will throw an IOException in case of errors
      int res = LibaioContext.open(file, direct);

      return new LibaioFile<>(res, null);
   }

   /**
    * Checks that the given argument is not null. If it is, throws {@link NullPointerException}.
    * Otherwise, returns the argument.
    */
   private static <T> T checkNotNull(T arg, String text) {
      if (arg == null) {
         throw new NullPointerException(text);
      }
      return arg;
   }

   public int poll(SubmitInfo[] callbacks, int min, int max) {
      if (min > max || min < 0 || max > queueSize) {
         throw new IllegalArgumentException("cannot request more events then the configured queueSize");
      }
      if (closed.get()) {
         return 0;
      }
      pollLock.lock();
      try {
         return unsafePoll(callbacks, min, max);
      } finally {
         pollLock.unlock();
      }
   }

   private int unsafePoll(SubmitInfo[] callbacks, int min, int max) {
      final AioRing aioRing = this.aioRing;
      final IoEventArray ioEventArray = this.ioEventArray;
      final Semaphore ioSpace = this.ioSpace;
      final PooledIOCB[] iocbArray = this.iocbArray;
      final Queue<PooledIOCB> iocbPool = this.iocbPool;
      final long ioContextAddress = this.ioContextAddress;
      final long ioEventArrayAddress = ioEventArray.address();
      int events = -1;
      if (aioRing != null) {
         events = aioRing.poll(ioEventArrayAddress, min, max);
      }
      if (events < min) {
         // perform a blocking call
         events = poll(ioContextAddress, ioEventArrayAddress, min, max);
      }
      assert events >= min && events <= max;
      for (int i = 0; i < events; i++) {
         final IoEventArray.IoEvent ioEvent = ioEventArray.get(i);
         assert ioEvent.obj() != 0;
         final int id = (int) ioEvent.data();
         final PooledIOCB pooledIOCB = iocbArray[id];
         assert ioEvent.obj() == pooledIOCB.address;
         assert IoCb.aioData(pooledIOCB.bytes) == id;
         SubmitInfo submitInfo = pooledIOCB.submitInfo;
         if (submitInfo != null) {
            pooledIOCB.submitInfo = null;
         }
         // NOTE:
         // First we return back the IOCB then we release the semaphore, to let submitInfo::done
         // to be able to issue a further write/read.
         iocbPool.add(pooledIOCB);
         if (ioSpace != null) {
            ioSpace.release();
         }
         if (callbacks != null) {
            // this could be NULL!
            callbacks[i] = submitInfo;
         }
         if (submitInfo != null) {
            final long res = ioEvent.res();
            if (res >= 0) {
               submitInfo.done();
            } else {
               // TODO the error string can be cached?
               submitInfo.onError((int) -res, strError(res));
            }
         }
      }
      return events;
   }

   /**
    * It will start polling and will keep doing until the context is closed.
    * This will call callbacks on {@link SubmitInfo#onError(int, String)} and
    * {@link SubmitInfo#done()}.
    * In case of error, both {@link SubmitInfo#onError(int, String)} and
    * {@link SubmitInfo#done()} are called.
    */
   public void poll() {
      if (closed.get()) {
         return;
      }
      pollLock.lock();
      try {
         final int dumbFD = this.dumbFD;
         final AioRing aioRing = this.aioRing;
         final IoEventArray ioEventArray = this.ioEventArray;
         final boolean useFdatasync = this.useFdatasync;
         final Semaphore ioSpace = this.ioSpace;
         final PooledIOCB[] iocbArray = this.iocbArray;
         final Queue<PooledIOCB> iocbPool = this.iocbPool;
         final int queueSize = this.queueSize;
         final long ioContextAddress = this.ioContextAddress;
         final long ioEventArrayAddress = ioEventArray.address();
         while (true) {
            int events = 0;
            if (aioRing != null) {
               events = aioRing.poll(ioEventArrayAddress, 0, queueSize);
            }
            // it could be either a RHEL bug or no new events
            if (events <= 0) {
               // blocked events here
               events = poll(ioContextAddress, ioEventArrayAddress, 1, queueSize);
            }
            assert events > 0 && events <= queueSize;
            boolean stop = false;
            int lastFile = -1;
            for (int i = 0; i < events; i++) {
               final IoEventArray.IoEvent ioEvent = ioEventArray.get(i);
               assert ioEvent.obj() != 0;
               final int id = (int) ioEvent.data();
               PooledIOCB pooledIOCB = iocbArray[id];
               assert ioEvent.obj() == pooledIOCB.address;
               assert IoCb.aioData(pooledIOCB.bytes) == id;
               final SubmitInfo submitInfo = pooledIOCB.submitInfo;
               if (submitInfo != null) {
                  pooledIOCB.submitInfo = null;
               }
               final long res = ioEvent.res();
               if (res >= 0) {
                  final int fd = IoCb.aioFildes(pooledIOCB.bytes);
                  if (fd != dumbFD) {
                     if (useFdatasync) {
                        if (lastFile != fd) {
                           lastFile = fd;
                           // anticipate these operations to release both earlier
                           iocbPool.add(pooledIOCB);
                           if (ioSpace != null) {
                              ioSpace.release();
                           }
                           pooledIOCB = null;
                           fdatasync(fd);
                        }
                     }
                  } else {
                     stop = true;
                  }
               }
               if (pooledIOCB != null) {
                  iocbPool.add(pooledIOCB);
                  if (ioSpace != null) {
                     ioSpace.release();
                  }
               }
               if (submitInfo != null) {
                  if (res >= 0) {
                     submitInfo.done();
                  } else {
                     // TODO the error string can be cached?
                     submitInfo.onError((int) -res, strError(res));
                  }
               }
            }
            if (stop) {
               return;
            }
         }
      } finally {
         pollLock.unlock();
      }
   }

   private static native String strError(long eventError);

   private static native int dumbFD();

   private static native long memoryAddress0(ByteBuffer buffer);

   /**
    * This is the queue for libaio, initialized with queueSize.
    */
   private native ByteBuffer newContext(int queueSize);

   /**
    * Internal method to be used when closing the controller.
    */
   private static native void deleteContext(ByteBuffer buffer);

   /**
    * it will return a file descriptor.
    *
    * @param path   the file name.
    * @param direct translates as O_DIRECT On open
    * @return a fd from open C call.
    */
   public static native int open(String path, boolean direct);

   public static native void close(int fd);

   /**
    */

   /**
    * Buffers for O_DIRECT need to use posix_memalign.
    * <br>
    * Documented at {@link LibaioFile#newBuffer(int)}.
    *
    * @param size      needs to be % alignment
    * @param alignment the alignment used at the dispositive
    * @return a new native buffer used with posix_memalign
    */
   public static native ByteBuffer newAlignedBuffer(int size, int alignment);

   /**
    * This will call posix free to release the inner buffer allocated at {@link #newAlignedBuffer(int, int)}.
    *
    * @param buffer a native buffer allocated with {@link #newAlignedBuffer(int, int)}.
    */
   public static native void freeBuffer(ByteBuffer buffer);

   private static native void submitWrite(int fd,
                           long ioContextAddress,
                           long iocbAddress,
                           long position,
                           int size,
                           long bufferAddress,
                           long requestId) throws IOException;

   private static native void submitRead(int fd,
                          long ioContextAddress,
                          long iocbAddress,
                          long position,
                          int size,
                          long bufferAddress,
                          long requestId) throws IOException;

   private static native void submitFDataSync(int fd,
                                              long ioContextAddress,
                                              long iocbAddress,
                                              long requestId) throws IOException;

   /**
    * Note: this shouldn't be done concurrently.
    * This method will block until the min condition is satisfied on the poll.
    * <p/>
    * The callbacks will include the original callback sent at submit (read or write).
    */
   private static native int poll(long ioContextAddress, long ioEventAddress, int min, int max);

   private static native int getNativeVersion();

   static native boolean lock(int fd);

   private static native void memsetBuffer(ByteBuffer buffer, int size);

   static native long getSize(int fd);

   static native int getBlockSizeFD(int fd);

   public static int getBlockSize(File path) {
      return getBlockSize(path.getAbsolutePath());
   }

   static native long getIOContextAddress(ByteBuffer ioControl);

   public static native int getBlockSize(String path);

   static native void fallocate(int fd, long size);

   static native void fill(int fd, int alignment, long size);

   static native void fdatasync(int fd);

   /**
    * Utility class built to detect runtime dependent features safely.
    */
   static final class RuntimeDependent {

      private static final long BUFFER_ADDRESS_FIELD_OFFSET;
      private static final boolean HAS_UNSAFE;

      static {
         boolean hasUnsafe = hasUnsafe();
         long bufferAddressFieldOffset = -1;
         if (hasUnsafe) {
            Field addressField = unsafeAddressField();
            if (addressField != null) {
               bufferAddressFieldOffset = unsafeAddressFiledOffset(addressField);
               hasUnsafe = true;
            } else {
               bufferAddressFieldOffset = -1;
               hasUnsafe = false;
            }
         }
         BUFFER_ADDRESS_FIELD_OFFSET = bufferAddressFieldOffset;
         HAS_UNSAFE = hasUnsafe;
      }

      static boolean hasUnsafe() {
         try {
            Unsafe unsafe = UnsafeAccess.UNSAFE;
            if (unsafe == null) {
               throw new NullPointerException();
            }
            return true;
         } catch (Throwable t) {
            return false;
         }
      }

      private static long unsafeAddressFiledOffset(Field addressField) {
         Objects.requireNonNull(addressField);
         return UnsafeAccess.UNSAFE.objectFieldOffset(addressField);
      }

      private static Field unsafeAddressField() {
         ByteBuffer direct = ByteBuffer.allocateDirect(1);
         final Object maybeAddressField = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
               try {
                  final Field field = Buffer.class.getDeclaredField("address");
                  // Use Unsafe to read value of the address field. This way it will not fail on JDK9+ which
                  // will forbid changing the access level via reflection.
                  final long offset = UnsafeAccess.UNSAFE.objectFieldOffset(field);
                  final long address = UnsafeAccess.UNSAFE.getLong(direct, offset);

                  // if direct really is a direct buffer, address will be non-zero
                  if (address == 0) {
                     return null;
                  }
                  return field;
               } catch (NoSuchFieldException e) {
                  return e;
               } catch (SecurityException e) {
                  return e;
               }
            }
         });

         if (maybeAddressField instanceof Field) {
            return (Field) maybeAddressField;
         } else {
            return null;
         }
      }

      public static <T> Queue<T> newMpmcQueue(int capacity) {
         capacity = Math.max(2, capacity);
         return HAS_UNSAFE ? new MpmcArrayQueue<>(capacity) : new MpmcAtomicArrayQueue<>(capacity);
      }

      public static long directBufferAddress(ByteBuffer byteBuffer) {
         if (HAS_UNSAFE) {
            return unsafeDirectBufferAddress(byteBuffer);
         }
         return memoryAddress0(byteBuffer);
      }

      private static long unsafeDirectBufferAddress(ByteBuffer buffer) {
         return UnsafeAccess.UNSAFE.getLong(buffer, BUFFER_ADDRESS_FIELD_OFFSET);
      }
   }
}

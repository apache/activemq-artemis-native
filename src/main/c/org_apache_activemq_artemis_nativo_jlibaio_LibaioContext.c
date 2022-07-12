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

#ifndef _GNU_SOURCE
// libaio, O_DIRECT and other things won't be available without this define
#define _GNU_SOURCE
#endif

//#define DEBUG

#include <jni.h>
#include <unistd.h>
#include <errno.h>
#include <libaio.h>
#include <sys/types.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdlib.h>
#include <pthread.h>
#include <limits.h>
#include <string.h>
#include "org_apache_activemq_artemis_nativo_jlibaio_LibaioContext.h"
#include "exception_helper.h"

//x86 has a strong memory model and there is no need of HW fences if just Write-Back (WB) memory is used
#define mem_barrier() __asm__ __volatile__ ("":::"memory")
#define read_barrier()	__asm__ __volatile__("":::"memory")
#define store_barrier()	__asm__ __volatile__("":::"memory")

struct io_control {
    io_context_t    ioContext;
    jobject         thisObject;
};

//These should be used to check if the user-space io_getevents is supported:
//Linux ABI for the ring buffer: https://elixir.bootlin.com/linux/v4.20.13/source/fs/aio.c#L54
//aio_read_events_ring: https://elixir.bootlin.com/linux/v4.20.13/source/fs/aio.c#L1148

// NOTE: if the kernel ever updates the structure, the RING-MAGIC will change and the code will switch back to normal IO calls
#define AIO_RING_MAGIC	0xa10a10a1
#define AIO_RING_INCOMPAT_FEATURES	0

// set this to 0 if you want to stop using ring reaping
#define RING_REAPER 1

jboolean forceSysCall = JNI_FALSE;

/*
 * Class:     org_apache_activemq_artemis_nativo_jlibaio_LibaioContext
 * Method:    setForceSyscall
 * Signature: (Z)V
 */
JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_setForceSyscall
  (JNIEnv * env, jclass clazz, jboolean _forceSysCall) {
  forceSysCall = _forceSysCall;
}

/*
 * Class:     org_apache_activemq_artemis_nativo_jlibaio_LibaioContext
 * Method:    isForceSyscall
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_isForceSyscall
  (JNIEnv * env, jclass clazz) {
  return forceSysCall | !RING_REAPER;
}


/** There is no defined aio_ring anywhere in an include,
    This is an implementation detail, that is a binary contract.
    it is safe to use the feature though. */
struct aio_ring {
	unsigned	        id;	                /* kernel internal index number */
	unsigned	        nr;	                /* number of io_events */
	unsigned	        head;
	unsigned	        tail;

	unsigned	        magic;
	unsigned	        compat_features;
	unsigned	        incompat_features;
	unsigned	        header_length;	    /* size of aio_ring */


	struct io_event		io_events[0];
}; /* 128 bytes + ring size */

// Check if the implementation supports AIO_RING by checking this number directly.
static inline int has_usable_ring(struct aio_ring *ring) {
    return ring->magic == AIO_RING_MAGIC && ring->incompat_features == AIO_RING_INCOMPAT_FEATURES;
}

// Newer versions of the kernel (newer here being a relative word, a couple years already at the time
// I am writing this), will have io_context_t as an opaque type, and the real type being the aio_ring.
static inline struct aio_ring* to_aio_ring(io_context_t aio_ctx) {
    return (struct aio_ring*) aio_ctx;
}

//It implements a user space batch read io events implementation that attempts to read io avoiding any sys calls
// This implementation will look at the internal structure (aio_ring) and move along the memory result
static inline int ringio_get_events(io_context_t aio_ctx, long min_nr, long max,
                                                       struct io_event *events, struct timespec *timeout) {
    struct aio_ring *ring = to_aio_ring(aio_ctx);
    //checks if it could be completed in user space, saving a sys call
    if (RING_REAPER && !forceSysCall && has_usable_ring(ring)) {
        const unsigned ring_nr = ring->nr;
        // We're assuming to be the exclusive writer to head, so we just need a compiler barrier
        unsigned head = ring->head;
        mem_barrier();
        const unsigned tail = ring->tail;
        //the kernel has written ring->tail from an interrupt:
        //we need to load acquire the completed events here
        read_barrier();
        int available = tail - head;
        if (available < 0) {
            //a wrap has occurred
            available += ring_nr;
        }
        #ifdef DEBUG
            fprintf(stdout, "tail = %d head= %d nr = %d available = %d\n", tail, head, ring_nr, available);
        #endif
        if ((available >= min_nr) || (timeout && timeout->tv_sec == 0 && timeout->tv_nsec == 0)) {
            if (!available) {
                return 0;
            }

            if (available > ring_nr) {
               // This is to trap a possible bug from the kernel:
               //       https://bugzilla.redhat.com/show_bug.cgi?id=1845326
               //       https://issues.apache.org/jira/browse/ARTEMIS-2800
               //
               // On the race available would eventually be >= max, while ring->tail was invalid
               // we could work around by waiting ring-tail to change:
               // while (ring->tail == tail) mem_barrier();
               //
               // however eventually we could have available==max in a legal situation what could lead to infinite loop here
               return io_getevents(aio_ctx, min_nr, max, events, timeout);

               // also: I could have called io_getevents to the one at the end of this method
               //       but I really hate goto, so I would rather have a duplicate code here
               //       and I did not want to create another memory flag to stop the rest of the code
            }

            const int available_nr = available < max? available : max;
            unsigned start = head;
            head += available;
            size_t available_bytes = available * sizeof(struct io_event);
            if (head < ring_nr) {
                // no wrap
                memcpy(&events[0], &ring->io_events[start], available_bytes);
            } else {
                head -= ring_nr;
                unsigned trail_size = (available - head);
                size_t trail_bytes = trail_size * sizeof(struct io_event);
                // copy trail
                memcpy(&events[0], &ring->io_events[start], trail_bytes);
                // copy header
                size_t header_bytes = available_bytes - trail_bytes;
                memcpy(&events[trail_size], &ring->io_events[0], header_bytes);
            }
            //it allow the kernel to build its own view of the ring buffer size
            //and push new events if there are any
            store_barrier();
            ring->head = head;
            #ifdef DEBUG
                fprintf(stdout, "consumed non sys-call = %d\n", available_nr);
            #endif
            return available_nr;
        }
    } else {
        #ifdef DEBUG
            fprintf(stdout, "The kernel is not supoprting the ring buffer any longer\n");
        #endif
    }
    // if this next line ever needs to be changed, beware of a duplicate code on this method
    // I explain why I duplicated the call instead of reuse it there ^^^^
    int sys_call_events = io_getevents(aio_ctx, min_nr, max, events, timeout);
    #ifdef DEBUG
        fprintf(stdout, "consumed sys-call = %d\n", sys_call_events);
    #endif
    return sys_call_events;
}

// We need a fast and reliable way to stop the blocked poller
// for that we need a dumb file,
// We are using a temporary file for this.
int dumbWriteHandler = 0;
char dumbPath[PATH_MAX];

#define ONE_MEGA 1048576l
void * oneMegaBuffer = 0;
pthread_mutex_t oneMegaMutex;

jclass libaioContextClass = NULL;
jclass runtimeExceptionClass = NULL;
jclass ioExceptionClass = NULL;

// util methods
void throwRuntimeException(JNIEnv* env, char* message) {
    (*env)->ThrowNew(env, runtimeExceptionClass, message);
}

void throwRuntimeExceptionErrorNo(JNIEnv* env, char* message, int errorNumber) {
    char* allocatedMessage = exceptionMessage(message, errorNumber);
    (*env)->ThrowNew(env, runtimeExceptionClass, allocatedMessage);
    free(allocatedMessage);
}

void throwIOException(JNIEnv* env, char* message) {
    (*env)->ThrowNew(env, ioExceptionClass, message);
}

void throwIOExceptionErrorNo(JNIEnv* env, char* message, int errorNumber) {
    char* allocatedMessage = exceptionMessage(message, errorNumber);
    (*env)->ThrowNew(env, ioExceptionClass, allocatedMessage);
    free(allocatedMessage);
}

void throwOutOfMemoryError(JNIEnv* env) {
    jclass exceptionClass = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    (*env)->ThrowNew(env, exceptionClass, "");
}

/** Notice: every usage of exceptionMessage needs to release the allocated memory for the sequence of char */
char* exceptionMessage(char* msg, int error) {
    if (error < 0) {
        // some functions return negative values
        // and it's hard to keep track of when to send -error and when not
        // this will just take care when things are forgotten
        // what would generate a proper error
        error = error * -1;
    }
    //strerror is returning a constant, so no need to free anything coming from strerror
    char *result = NULL;

    if (asprintf(&result, "%s%s", msg, strerror(error)) == -1) {
    	fprintf(stderr, "Could not allocate enough memory for the error message: %s/%s\n", msg, strerror(error));
    }

    return result;
}

static inline short verifyBuffer(int alignment) {
    pthread_mutex_lock(&oneMegaMutex);

    if (oneMegaBuffer == 0) {
       #ifdef DEBUG
          fprintf (stdout, "oneMegaBuffer %ld\n", (long) oneMegaBuffer);
       #endif
       if (posix_memalign(&oneMegaBuffer, alignment, ONE_MEGA) != 0) {
            fprintf(stderr, "Could not allocate the 1 Mega Buffer for initializing files\n");
            pthread_mutex_unlock(&oneMegaMutex);
            return -1;
       }
        memset(oneMegaBuffer, 0, ONE_MEGA);
    }

    pthread_mutex_unlock(&oneMegaMutex);

    return 0;

}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    } else {
        int res = pthread_mutex_init(&oneMegaMutex, 0);
        if (res) {
             fprintf(stderr, "could not initialize mutex on on_load, %d", res);
             return JNI_ERR;
        }
        sprintf (dumbPath, "%s/artemisJLHandler_XXXXXX", P_tmpdir);
        dumbWriteHandler = mkstemp (dumbPath);

        #ifdef DEBUG
           fprintf (stdout, "Creating temp file %s for dumb writes\n", dumbPath);
           fflush(stdout);
        #endif

        if (dumbWriteHandler < 0) {
           fprintf (stderr, "couldn't create stop file handler %s\n", dumbPath);
           return JNI_ERR;
        }

        //
        // Accordingly to previous experiences we must hold Global Refs on Classes
        // And
        //
        // Accordingly to IBM recommendations here:
        // We don't need to hold a global reference on methods:
        // http://www.ibm.com/developerworks/java/library/j-jni/index.html#notc
        // Which actually caused core dumps

        jclass localRuntimeExceptionClass = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (localRuntimeExceptionClass == NULL) {
            // pending exception...
            return JNI_ERR;
        }
        runtimeExceptionClass = (jclass) (*env)->NewGlobalRef(env, localRuntimeExceptionClass);
        if (runtimeExceptionClass == NULL) {
            // out-of-memory!
            throwOutOfMemoryError(env);
            return JNI_ERR;
        }

        jclass localIoExceptionClass = (*env)->FindClass(env, "java/io/IOException");
        if (localIoExceptionClass == NULL) {
            // pending exception...
            return JNI_ERR;
        }
        ioExceptionClass = (jclass) (*env)->NewGlobalRef(env, localIoExceptionClass);
        if (ioExceptionClass == NULL) {
            // out-of-memory!
            throwOutOfMemoryError(env);
            return JNI_ERR;
        }

        libaioContextClass = (*env)->FindClass(env, "org/apache/activemq/artemis/nativo/jlibaio/LibaioContext");
        if (libaioContextClass == NULL) {
           return JNI_ERR;
        }
        libaioContextClass = (jclass)(*env)->NewGlobalRef(env, (jobject)libaioContextClass);

        return JNI_VERSION_1_6;
    }
}

static inline void closeDumbHandlers() {
    if (dumbWriteHandler != 0) {
        #ifdef DEBUG
           fprintf (stdout, "Closing and removing dump handler %s\n", dumbPath);
        #endif
        dumbWriteHandler = 0;
        close(dumbWriteHandler);
        unlink(dumbPath);
    }
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        // Something is wrong but nothing we can do about this :(
        return;
    } else {
        closeDumbHandlers();

        if (oneMegaBuffer != 0) {
           free(oneMegaBuffer);
           oneMegaBuffer = 0;
        }

        pthread_mutex_destroy(&oneMegaMutex);

        // delete global references so the GC can collect them
        if (runtimeExceptionClass != NULL) {
            (*env)->DeleteGlobalRef(env, runtimeExceptionClass);
        }
        if (ioExceptionClass != NULL) {
            (*env)->DeleteGlobalRef(env, ioExceptionClass);
        }

        if (libaioContextClass != NULL) {
            (*env)->DeleteGlobalRef(env, (jobject)libaioContextClass);
        }
    }
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_shutdownHook
  (JNIEnv * env, jclass clazz) {
    closeDumbHandlers();
}


static inline struct io_control * getIOControl(JNIEnv* env, jobject pointer) {
    struct io_control * ioControl = (struct io_control *) (*env)->GetDirectBufferAddress(env, pointer);
    if (ioControl == NULL) {
       throwRuntimeException(env, "Controller not initialized");
    }
    return ioControl;
}

JNIEXPORT jlong JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_getIOContextAddress(JNIEnv* env, jclass clazz, jobject ioControlBuffer) {
    return (jlong) (getIOControl(env, ioControlBuffer)->ioContext);
}

static inline short submit(JNIEnv * env, io_context_t io_context, struct iocb * iocb) {
    int result = io_submit(io_context, 1, &iocb);

    if (result < 0) {
        throwIOExceptionErrorNo(env, "Error while submitting IO: ", -result);
        return 0;
    }

    return 1;
}

JNIEXPORT jboolean JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_lock
  (JNIEnv * env, jclass  clazz, jint handle) {
    return flock(handle, LOCK_EX | LOCK_NB) == 0;
}

/**
 * Everything that is allocated here will be freed at deleteContext when the class is unloaded.
 */
JNIEXPORT jobject JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_newContext(JNIEnv* env, jobject thisObject, jint queueSize) {
    #ifdef DEBUG
        fprintf (stdout, "Initializing context\n");
    #endif

	struct io_control * theControl = (struct io_control *) malloc(sizeof(struct io_control));
    if (theControl == NULL) {
        throwOutOfMemoryError(env);
        return NULL;
    }

	int res = io_queue_init(queueSize, &theControl->ioContext);
	if (res) {
		// Error, so need to release whatever was done before
        io_queue_release(theControl->ioContext);
        free(theControl);

		throwRuntimeExceptionErrorNo(env, "Cannot initialize queue:", res);
		return NULL;
	}
    theControl->thisObject = (*env)->NewGlobalRef(env, thisObject);

    return (*env)->NewDirectByteBuffer(env, theControl, sizeof(struct io_control));
}

JNIEXPORT jstring JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_strError(JNIEnv* env, jclass clazz, jlong eventResult) {
    return (*env)->NewStringUTF(env, strerror(-eventResult));
}

JNIEXPORT jint JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_dumbFD(JNIEnv* env, jclass clazz) {
    return dumbWriteHandler;
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_deleteContext(JNIEnv* env, jclass clazz, jobject ioControlBuffer) {
    struct io_control * theControl = getIOControl(env, ioControlBuffer);
    if (theControl == NULL) {
      return;
    }

    io_queue_release(theControl->ioContext);

    (*env)->DeleteGlobalRef(env, theControl->thisObject);

    free(theControl);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_close(JNIEnv* env, jclass clazz, jint fd) {
   if (close(fd) < 0) {
       throwIOExceptionErrorNo(env, "Error closing file:", errno);
   }
}

JNIEXPORT int JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_open(JNIEnv* env, jclass clazz,
                        jstring path, jboolean direct) {
    const char* f_path = (*env)->GetStringUTFChars(env, path, 0);

    int res;
    if (direct) {
      res = open(f_path, O_RDWR | O_CREAT | O_DIRECT, 0666);
    } else {
      res = open(f_path, O_RDWR | O_CREAT, 0666);
    }

    (*env)->ReleaseStringUTFChars(env, path, f_path);

    if (res < 0) {
       throwIOExceptionErrorNo(env, "Cannot open file:", errno);
    }

    return res;
}

JNIEXPORT jlong Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_memoryAddress0(JNIEnv* env, jclass clazz, jobject buffer) {
    return (jlong) (*env)->GetDirectBufferAddress(env, buffer);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_submitWrite
  (JNIEnv * env, jclass clazz, jint fileHandle, jlong ioContextAddress, jlong iocbAddress, jlong position, jint size, jlong bufferAddress, jlong requestId) {
    #ifdef DEBUG
       fprintf (stdout, "submitWrite position %ld, size %d\n", position, size);
    #endif

    io_context_t io_context = (io_context_t) ioContextAddress;

    struct iocb * iocb = (struct iocb *) iocbAddress;

    io_prep_pwrite(iocb, fileHandle, (void *)bufferAddress, (size_t)size, position);

    iocb->data = (void *)requestId;

    submit(env, io_context, iocb);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_submitFDataSync
  (JNIEnv * env, jclass clazz, jint fileHandle, jlong ioContextAddress, jlong iocbAddress, jlong requestId) {
    #ifdef DEBUG
       fprintf (stdout, "submitFDataSync fd %d\n", (int)fileHandle);
    #endif

    io_context_t io_context = (io_context_t) ioContextAddress;

    struct iocb * iocb = (struct iocb *) iocbAddress;

    io_prep_fdsync(iocb, fileHandle);

    iocb->data = (void *)requestId;

    submit(env, io_context, iocb);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_submitRead
  (JNIEnv * env, jclass clazz, jint fileHandle, jlong ioContextAddress, jlong iocbAddress, jlong position, jint size, jlong bufferAddress, jlong requestId) {
    #ifdef DEBUG
        fprintf (stdout, "submitRead position %ld, size %d\n", position, size);
    #endif
    io_context_t io_context = (io_context_t) ioContextAddress;

    struct iocb * iocb = (struct iocb *) iocbAddress;

    io_prep_pread(iocb, fileHandle, (void *)bufferAddress, (size_t)size, position);

    iocb->data = (void *)requestId;

    submit(env, io_context, iocb);
}

JNIEXPORT jint JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_poll
  (JNIEnv * env, jclass clazz, jlong ioContextAddress, jlong ioEventsAddress, jint min, jint max) {
    io_context_t io_context = (io_context_t) ioContextAddress;
    struct io_event * events = (struct io_event *) ioEventsAddress;
    return ringio_get_events(io_context, min, max, events, 0);
}

JNIEXPORT jobject JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_newAlignedBuffer
(JNIEnv * env, jclass clazz, jint size, jint alignment) {
    if (size % alignment != 0) {
        throwRuntimeException(env, "Buffer size needs to be aligned to passed argument");
        return NULL;
    }

    // This will allocate a buffer, aligned by alignment.
    // Buffers created here need to be manually destroyed by destroyBuffer, or this would leak on the process heap away of Java's GC managed memory
    // NOTE: this buffer will contain non initialized data, you must fill it up properly
    void * buffer;
    int result = posix_memalign(&buffer, (size_t)alignment, (size_t)size);

    if (result) {
        throwRuntimeExceptionErrorNo(env, "Can't allocate posix buffer:", result);
        return NULL;
    }

    memset(buffer, 0, (size_t)size);

    return (*env)->NewDirectByteBuffer(env, buffer, size);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_fdatasync(JNIEnv * env, jclass clazz, jint fd) {
    fdatasync(fd);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_freeBuffer
  (JNIEnv * env, jclass clazz, jobject jbuffer) {
    if (jbuffer == NULL)
    {
       throwRuntimeException(env, "Null pointer");
       return;
    }
  	void *  buffer = (*env)->GetDirectBufferAddress(env, jbuffer);
  	free(buffer);
}


/** It does nothing... just return true to make sure it has all the binary dependencies */
JNIEXPORT jint JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_getNativeVersion
  (JNIEnv * env, jclass clazz)

{
     return org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_EXPECTED_NATIVE_VERSION;
}

JNIEXPORT jlong JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_getSize
  (JNIEnv * env, jclass clazz, jint fd)
{
    struct stat statBuffer;

    if (fstat(fd, &statBuffer) < 0)
    {
        throwIOExceptionErrorNo(env, "Cannot determine file size:", errno);
        return -1l;
    }
    return statBuffer.st_size;
}

JNIEXPORT jint JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_getBlockSizeFD
  (JNIEnv * env, jclass clazz, jint fd)
{
    struct stat statBuffer;

    if (fstat(fd, &statBuffer) < 0)
    {
        throwIOExceptionErrorNo(env, "Cannot determine file size:", errno);
        return -1l;
    }
    return statBuffer.st_blksize;
}

JNIEXPORT jint JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_getBlockSize
  (JNIEnv * env, jclass clazz, jstring path)
{
    const char* f_path = (*env)->GetStringUTFChars(env, path, 0);
    struct stat statBuffer;

    if (stat(f_path, &statBuffer) < 0)
    {
        throwIOExceptionErrorNo(env, "Cannot determine file size:", errno);
        return -1l;
    }

    (*env)->ReleaseStringUTFChars(env, path, f_path);

    return statBuffer.st_blksize;
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_fallocate
  (JNIEnv * env, jclass clazz, jint fd, jlong size)
{
    if (fallocate(fd, 0, 0, (off_t) size) < 0)
    {
        throwIOExceptionErrorNo(env, "Could not preallocate file", errno);
    }
    fsync(fd);
    lseek (fd, 0, SEEK_SET);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_fill
  (JNIEnv * env, jclass clazz, jint fd, jint alignment, jlong size)
{

    int i;
    int blocks = size / ONE_MEGA;
    int rest = size % ONE_MEGA;

    #ifdef DEBUG
        fprintf (stdout, "calling fill ... blocks = %d, rest=%d, alignment=%d\n", blocks, rest, alignment);
    #endif


    verifyBuffer(alignment);

    lseek (fd, 0, SEEK_SET);
    for (i = 0; i < blocks; i++)
    {
        if (write(fd, oneMegaBuffer, ONE_MEGA) < 0)
        {
            #ifdef DEBUG
               fprintf (stdout, "Errno is %d\n", errno);
            #endif
            throwIOException(env, "Cannot initialize file");
            return;
        }
    }

    if (rest != 0l)
    {
       if (write(fd, oneMegaBuffer, rest) < 0)
       {
            #ifdef DEBUG
               fprintf (stdout, "Errno is %d\n", errno);
            #endif
           throwIOException(env, "Cannot initialize file with final rest");
           return;
       }
    }
    lseek (fd, 0, SEEK_SET);
}

JNIEXPORT void JNICALL Java_org_apache_activemq_artemis_nativo_jlibaio_LibaioContext_memsetBuffer
  (JNIEnv *env, jclass clazz, jobject jbuffer, jint size)
{
    #ifdef DEBUG
        fprintf (stdout, "Mem setting buffer with %d bytes\n", size);
    #endif
    void * buffer = (*env)->GetDirectBufferAddress(env, jbuffer);

    if (buffer == 0)
    {
        throwRuntimeException(env, "Invalid Buffer used, libaio requires NativeBuffer instead of Java ByteBuffer");
        return;
    }

    memset(buffer, 0, (size_t)size);
}

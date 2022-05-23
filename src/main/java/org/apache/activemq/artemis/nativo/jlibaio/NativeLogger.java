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

public class NativeLogger {

   public static final String PROJECT_PREFIX = "jlibaio";

   private static LoggerCallback loggerCallback = new SystemCallback();

   public static void setLoggerCallback(LoggerCallback callback) {
      loggerCallback = callback;
   }

   private static final int DIFFERENT_VERSION_ID = 163001;
   private static final String DIFFERENT_VERSION = PROJECT_PREFIX + DIFFERENT_VERSION_ID + " You have a native library with a different version than expected";

   public final static void incompatibleNativeLibrary() {
       warn(DIFFERENT_VERSION);
   }

   public final static void info(String message) {
      loggerCallback.info(message);
   }

   public final static void warn(String message) {
      loggerCallback.warn(message);
   }

   public final static void warn(String message, Throwable e) {
      loggerCallback.warn(message, e);
   }

   public final static void debug(String message) {
      loggerCallback.debug(message);
   }

   public final static void debug(String message, Throwable e) {
      loggerCallback.debug(message, e);
   }
}

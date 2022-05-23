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

/** This will use System.err for warn and System.out for info */
public class SystemCallback implements LoggerCallback {

   private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty(SystemCallback.class.getName() + ".DEBUG", "false"));

   public void debug(String message, Throwable e) {
      if (DEBUG) {
         System.out.println("Debug from ArtemisNative: " + message);
         e.printStackTrace(System.out);
      }
   }

   public void debug(String message) {
      if (DEBUG) {
         System.out.println("Debug from ArtemisNative: " + message);
      }
   }

   public void info(String message) {
      System.out.println("Information from ArtemisNative: " + message);
   }

   public void warn(String message) {
      System.err.println("Warning from ArtemisNative: " + message);
   }

   public void warn(String message, Throwable e) {
      System.err.println("Warning from ArtemisNative: " + message);
      e.printStackTrace(System.err);
   }
}

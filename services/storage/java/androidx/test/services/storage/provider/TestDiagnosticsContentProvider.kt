/*
 * Copyright (C) 2020 The Android Open Source Project
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

package androidx.test.services.storage.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.test.services.storage.TestStorageConstants
import androidx.test.services.storage.TestStorageServiceProto.TestArguments
import com.google.testing.platform.android.core.orchestration.strategy.GrpcDiagnosticsOrchestrationStrategy
import com.google.testing.platform.core.telemetry.android.opencensus.exporter.SpanDataWrapper
import com.google.testing.platform.core.telemetry.opencensus.TraceProtoUtils
import com.google.testing.platform.lib.coroutines.scope.JobScope
import io.grpc.android.AndroidChannelBuilder
import io.opencensus.trace.export.SpanData
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.ObjectInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class TestDiagnosticsContentProvider : ContentProvider() {

  private lateinit var serverPort: String
  private lateinit var grpcDiagnosticsOrchestrationStrategy: GrpcDiagnosticsOrchestrationStrategy

  companion object {
    var count = 0
    private const val INVALID_SERVER_PORT = 0
    private const val DEFAULT_SERVER_PORT = 64676

    private fun getServerPortFromTestArgs(): Int {
      val testArgsFile = File(
        Environment.getExternalStorageDirectory(),
        TestStorageConstants.ON_DEVICE_PATH_INTERNAL_USE + TestStorageConstants.TEST_ARGS_FILE_NAME
      )
      if (!testArgsFile.exists()) {
        return INVALID_SERVER_PORT
      }
      try {
        val testArgs = TestArguments.parseFrom(FileInputStream(testArgsFile))

        var serverPort: String? = testArgs.argList.find {
          it.name == "diagnosticsServerPort"
        }?.value

        // If we receive an INVALID_SERVER_PORT use the default port instead
        serverPort?.let {
          if (it == INVALID_SERVER_PORT.toString()) {
            DEFAULT_SERVER_PORT.toString()
          } else {
            serverPort
          }
        }

        // If we receive no port, set it to the default port
        serverPort = serverPort ?: INVALID_SERVER_PORT.toString()
        return serverPort.toInt()
      } catch (e: IOException) {
        throw RuntimeException("Not able to read from file: " + testArgsFile.name, e)
      }
    }
  }

  override fun onCreate(): Boolean {
    return false
  }

  override fun query(
    uri: Uri,
    strings: Array<String>?,
    s: String?,
    strings1: Array<String>?,
    s1: String?
  ): Cursor? {
    return null
  }

  override fun getType(uri: Uri): String? {
    return null
  }

  override fun insert(uri: Uri, contentValues: ContentValues?): Uri? {
    val ois = ObjectInputStream(ByteArrayInputStream(contentValues!!.getAsByteArray("span")))
    val receivedSpan = SpanDataWrapper.readObject(ois)
    Log.i("DiagnosticsCP", "Received span : ${receivedSpan.name}")

    if (!::serverPort.isInitialized) {
      serverPort = getServerPortFromTestArgs().toString()
      if (serverPort.toInt() == INVALID_SERVER_PORT) {
        return null
      }

      try {
        connectToAtpServer()
      } catch (t: Throwable) {
        Log.w("DiagnosticsCP", "Connecting to the diagnostics service resulted in an error: $t")
        return null
      }
    }
    sendDiagnosticsEvent(receivedSpan)
    return null
  }

  override fun delete(uri: Uri, s: String?, strings: Array<String>?): Int {
    return 0
  }

  override fun update(
    uri: Uri,
    contentValues: ContentValues?,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }

  private fun connectToAtpServer() {
    grpcDiagnosticsOrchestrationStrategy = GrpcDiagnosticsOrchestrationStrategy(
      JobScope(Dispatchers.Default)
    ) { target ->
      AndroidChannelBuilder.forTarget(target).context(context)
    }
    grpcDiagnosticsOrchestrationStrategy.start(serverPort.toInt())
    Log.i("DiagnosticsCP", "Started server with port : ${serverPort.toInt()}")
  }

  private fun sendDiagnosticsEvent(spanData: SpanData) {
    try {
      runBlocking {
        grpcDiagnosticsOrchestrationStrategy.spanMessages.send(
          TraceProtoUtils.toSpanProto(spanData)
        )
      }
    } catch (t: Throwable) {
      Log.w(
        "DiagnosticsCP",
        "Sending events to the diagnostics service resulted in an error: $t"
      )
    }
  }
}

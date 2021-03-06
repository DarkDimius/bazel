// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote.logging;

import com.google.devtools.build.lib.remote.logging.RemoteExecutionLog.LogEntry;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import javax.annotation.Nullable;

/** Client interceptor for logging details of certain gRPC calls. */
public class LoggingInterceptor implements ClientInterceptor {

  /**
   * Returns a {@link LoggingHandler} to handle logging details for the specified method. If there
   * is no handler for the given method, returns {@code null}.
   *
   * @param method Method to return handler for.
   */
  protected <ReqT, RespT> @Nullable LoggingHandler<ReqT, RespT> selectHandler(
      MethodDescriptor<ReqT, RespT> method) {
    // TODO(cdlee): add handlers for methods
    return null;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
    LoggingHandler<ReqT, RespT> handler = selectHandler(method);
    if (handler != null) {
      return new LoggingForwardingCall<>(call, handler, method);
    } else {
      return call;
    }
  }

  /**
   * Wraps client call to log call details by building a {@link LogEntry} and writing it to a log.
   */
  private static class LoggingForwardingCall<ReqT, RespT>
      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {
    private final LoggingHandler<ReqT, RespT> handler;
    private final LogEntry.Builder entryBuilder;

    protected LoggingForwardingCall(
        ClientCall<ReqT, RespT> delegate,
        LoggingHandler<ReqT, RespT> handler,
        MethodDescriptor<ReqT, RespT> method) {
      super(delegate);
      this.handler = handler;
      this.entryBuilder = LogEntry.newBuilder().setMethodName(method.getFullMethodName());
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      RequestMetadata metadata = TracingMetadataUtils.requestMetadataFromHeaders(headers);
      if (metadata != null) {
        entryBuilder.setMetadata(metadata);
      }
      super.start(
          new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
              responseListener) {
            @Override
            public void onMessage(RespT message) {
              handler.handleResp(message);
              super.onMessage(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              entryBuilder.setStatus(makeStatusProto(status));
              // TODO(cdlee): Actually store this and log the entry.
              entryBuilder.mergeFrom(handler.getEntry()).build();
              super.onClose(status, trailers);
            }
          },
          headers);
    }

    @Override
    public void sendMessage(ReqT message) {
      handler.handleReq(message);
      super.sendMessage(message);
    }
  }

  /** Converts io.grpc.Status to com.google.rpc.Status proto for logging. */
  private static com.google.rpc.Status makeStatusProto(Status status) {
    String message = "";
    if (status.getCause() != null) {
      message = status.getCause().toString();
    } else if (status.getDescription() != null) {
      message = status.getDescription();
    }
    return com.google.rpc.Status.newBuilder()
        .setCode(status.getCode().value())
        .setMessage(message)
        .build();
  }
}

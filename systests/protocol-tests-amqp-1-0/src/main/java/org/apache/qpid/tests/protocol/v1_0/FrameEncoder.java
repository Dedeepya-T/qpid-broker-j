/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.qpid.tests.protocol.v1_0;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.v1_0.codec.FrameWriter;
import org.apache.qpid.server.protocol.v1_0.framing.AMQFrame;
import org.apache.qpid.server.protocol.v1_0.type.codec.AMQPDescribedTypeRegistry;
import org.apache.qpid.server.transport.ByteBufferSender;
import org.apache.qpid.tests.protocol.OutputEncoder;

public class FrameEncoder implements OutputEncoder
{
    private static final AMQPDescribedTypeRegistry TYPE_REGISTRY = AMQPDescribedTypeRegistry.newInstance()
                                                                                            .registerTransportLayer()
                                                                                            .registerMessagingLayer()
                                                                                            .registerTransactionLayer()
                                                                                            .registerSecurityLayer()
                                                                                            .registerExtensionSoleconnLayer();

    @Override
    public ByteBuffer encode(final Object msg)
    {
        if (msg instanceof AMQFrame)
        {
            List<ByteBuffer> buffers = new ArrayList<>();
            FrameWriter _frameWriter = new FrameWriter(TYPE_REGISTRY, new ByteBufferSender()
            {
                @Override
                public boolean isDirectBufferPreferred()
                {
                    return false;
                }

                @Override
                public void send(final QpidByteBuffer msg)
                {
                    byte[] data = new byte[msg.remaining()];
                    msg.get(data);
                    buffers.add(ByteBuffer.wrap(data));
                }

                @Override
                public void flush()
                {
                }

                @Override
                public void close()
                {

                }
            });
            _frameWriter.send(((AMQFrame) msg));

            int remaining = 0;
            for (ByteBuffer byteBuffer: buffers)
            {
                remaining += byteBuffer.remaining();
            }
            ByteBuffer result = ByteBuffer.allocate(remaining);
            for (ByteBuffer byteBuffer: buffers)
            {
                result.put(byteBuffer);
            }
            result.flip();
            return result;
        }
        return null;
    }
}

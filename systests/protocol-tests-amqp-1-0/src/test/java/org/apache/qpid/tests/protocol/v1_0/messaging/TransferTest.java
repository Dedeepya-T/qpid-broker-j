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

package org.apache.qpid.tests.protocol.v1_0.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hamcrest.CoreMatchers;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.v1_0.type.Binary;
import org.apache.qpid.server.protocol.v1_0.type.ErrorCarryingFrameBody;
import org.apache.qpid.server.protocol.v1_0.type.Outcome;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedInteger;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedLong;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Accepted;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Header;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Received;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Rejected;
import org.apache.qpid.server.protocol.v1_0.type.transport.AmqpError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Attach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Begin;
import org.apache.qpid.server.protocol.v1_0.type.transport.Close;
import org.apache.qpid.server.protocol.v1_0.type.transport.Detach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Disposition;
import org.apache.qpid.server.protocol.v1_0.type.transport.End;
import org.apache.qpid.server.protocol.v1_0.type.transport.Error;
import org.apache.qpid.server.protocol.v1_0.type.transport.Flow;
import org.apache.qpid.server.protocol.v1_0.type.transport.LinkError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Open;
import org.apache.qpid.server.protocol.v1_0.type.transport.ReceiverSettleMode;
import org.apache.qpid.server.protocol.v1_0.type.transport.Role;
import org.apache.qpid.server.protocol.v1_0.type.transport.SenderSettleMode;
import org.apache.qpid.server.protocol.v1_0.type.transport.Transfer;
import org.apache.qpid.tests.protocol.v1_0.FrameTransport;
import org.apache.qpid.tests.protocol.v1_0.Interaction;
import org.apache.qpid.tests.protocol.v1_0.MessageDecoder;
import org.apache.qpid.tests.protocol.v1_0.MessageEncoder;
import org.apache.qpid.tests.protocol.SpecificationTest;
import org.apache.qpid.tests.protocol.Response;
import org.apache.qpid.tests.utils.BrokerAdmin;
import org.apache.qpid.tests.utils.BrokerAdminUsingTestBase;

public class TransferTest extends BrokerAdminUsingTestBase
{
    private static final String TEST_MESSAGE_DATA = "foo";
    private static final long MAX_MAX_MESSAGE_SIZE_WE_ARE_WILLING_TO_TEST = 200 * 1024 * 1024L;
    private InetSocketAddress _brokerAddress;
    private String _originalMmsMessageStorePersistence;

    @Before
    public void setUp()
    {
        _originalMmsMessageStorePersistence = System.getProperty("qpid.tests.mms.messagestore.persistence");
        System.setProperty("qpid.tests.mms.messagestore.persistence", "false");

        getBrokerAdmin().createQueue(BrokerAdmin.TEST_QUEUE_NAME);
        _brokerAddress = getBrokerAdmin().getBrokerAddress(BrokerAdmin.PortType.ANONYMOUS_AMQP);
    }

    @After
    public void tearDown()
    {
        if (_originalMmsMessageStorePersistence != null)
        {
            System.setProperty("qpid.tests.mms.messagestore.persistence", _originalMmsMessageStorePersistence);
        }
        else
        {
            System.clearProperty("qpid.tests.mms.messagestore.persistence");
        }
    }

    @Test
    @SpecificationTest(section = "1.3.4",
            description = "Transfer without mandatory fields should result in a decoding error.")
    public void emptyTransfer() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            Close responseClose = transport.newInteraction()
                                           .negotiateProtocol().consumeResponse()
                                           .open().consumeResponse(Open.class)
                                           .begin().consumeResponse(Begin.class)
                                           .attachRole(Role.SENDER)
                                           .attach().consumeResponse(Attach.class)
                                           .consumeResponse(Flow.class)
                                           .transferHandle(null)
                                           .transfer()
                                           .consumeResponse()
                                           .getLatestResponse(Close.class);
            assertThat(responseClose.getError(), is(notNullValue()));
            assertThat(responseClose.getError().getCondition(), equalTo(AmqpError.DECODE_ERROR));
        }
    }

    @Test
    @SpecificationTest(section = "2.7.5",
            description = "[delivery-tag] MUST be specified for the first transfer "
                          + "[...] and can only be omitted for continuation transfers.")
    public void transferWithoutDeliveryTag() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            Interaction interaction = transport.newInteraction()
                                                 .negotiateProtocol().consumeResponse()
                                                 .open().consumeResponse(Open.class)
                                                 .begin().consumeResponse(Begin.class)
                                                 .attachRole(Role.SENDER)
                                                 .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                 .attach().consumeResponse(Attach.class)
                                                 .consumeResponse(Flow.class)
                                                 .transferDeliveryTag(null)
                                                 .transferPayloadData("testData")
                                                 .transfer();
            interaction.consumeResponse(Detach.class, End.class, Close.class);
        }
    }

    @Test
    @SpecificationTest(section = "2.6.12",
            description = "Transferring A Message.")
    public void transferUnsettled() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            Disposition responseDisposition = transport.newInteraction()
                                                       .negotiateProtocol().consumeResponse()
                                                       .open().consumeResponse(Open.class)
                                                       .begin().consumeResponse(Begin.class)
                                                       .attachRole(Role.SENDER)
                                                       .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                       .attachHandle(linkHandle)
                                                       .attach().consumeResponse(Attach.class)
                                                       .consumeResponse(Flow.class)
                                                       .transferHandle(linkHandle)
                                                       .transferPayloadData("testData")
                                                       .transfer()
                                                       .consumeResponse()
                                                       .getLatestResponse(Disposition.class);
            assertThat(responseDisposition.getRole(), is(Role.RECEIVER));
            assertThat(responseDisposition.getSettled(), is(Boolean.TRUE));
            assertThat(responseDisposition.getState(), is(instanceOf(Accepted.class)));
        }
    }

    @Test
    @SpecificationTest(section = "2.7.5",
            description = "If first, this indicates that the receiver MUST settle the delivery once it has arrived without waiting for the sender to settle first")
    public void transferReceiverSettleModeFirst() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            Disposition responseDisposition = transport.newInteraction()
                                                       .negotiateProtocol().consumeResponse()
                                                       .open().consumeResponse(Open.class)
                                                       .begin().consumeResponse(Begin.class)
                                                       .attachRole(Role.SENDER)
                                                       .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                       .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                                                       .attach().consumeResponse(Attach.class)
                                                       .consumeResponse(Flow.class)
                                                       .transferPayloadData("testData")
                                                       .transferRcvSettleMode(ReceiverSettleMode.FIRST)
                                                       .transfer()
                                                       .consumeResponse()
                                                       .getLatestResponse(Disposition.class);
            assertThat(responseDisposition.getRole(), is(Role.RECEIVER));
            assertThat(responseDisposition.getSettled(), is(Boolean.TRUE));
            assertThat(responseDisposition.getState(), is(instanceOf(Accepted.class)));
        }
    }

    @Test
    @SpecificationTest(section = "2.7.5",
            description = "If the negotiated link value is first, then it is illegal to set this field to second.")
    public void transferReceiverSettleModeCannotBeSecondWhenLinkModeIsFirst() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            Detach detach = transport.newInteraction()
                                     .negotiateProtocol().consumeResponse()
                                     .open().consumeResponse(Open.class)
                                     .begin().consumeResponse(Begin.class)
                                     .attachRole(Role.SENDER)
                                     .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                     .attachRcvSettleMode(ReceiverSettleMode.FIRST)
                                     .attach().consumeResponse(Attach.class)
                                     .consumeResponse(Flow.class)
                                     .transferPayloadData("testData")
                                     .transferRcvSettleMode(ReceiverSettleMode.SECOND)
                                     .transfer()
                                     .consumeResponse()
                                     .getLatestResponse(Detach.class);
            Error error = detach.getError();
            assertThat(error, is(notNullValue()));
            assertThat(error.getCondition(), is(equalTo(AmqpError.INVALID_FIELD)));
        }
    }

    @Test
    @SpecificationTest(section = "", description = "Pipelined message send")
    public void presettledPipelined() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction();
            interaction.negotiateProtocol()
                       .open()
                       .begin()
                       .attachRole(Role.SENDER)
                       .attach()
                       .transferPayloadData("testData")
                       .transferSettled(true)
                       .transfer()
                       .close()
                       .sync();

            final byte[] protocolResponse = interaction.consumeResponse().getLatestResponse(byte[].class);
            assertThat(protocolResponse, is(equalTo("AMQP\0\1\0\0".getBytes(UTF_8))));

            interaction.consumeResponse().getLatestResponse(Open.class);
            interaction.consumeResponse().getLatestResponse(Begin.class);
            interaction.consumeResponse().getLatestResponse(Attach.class);
            interaction.consumeResponse().getLatestResponse(Flow.class);
            //interaction.consumeResponse(null, Disposition.class, Detach.class, End.class);
            interaction.consumeResponse().getLatestResponse(Close.class);
        }
    }

    @Test
    @SpecificationTest(section = "3.2.1",
            description = "Durable messages MUST NOT be lost even if an intermediary is unexpectedly terminated and "
                          + "restarted. A target which is not capable of fulfilling this guarantee MUST NOT accept messages "
                          + "where the durable header is set to true: if the source allows the rejected outcome then the "
                          + "message SHOULD be rejected with the precondition-failed error, otherwise the link MUST be "
                          + "detached by the receiver with the same error.")
    public void durableTransferWithRejectedOutcome() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            MessageEncoder messageEncoder = new MessageEncoder();
            final Header header = new Header();
            header.setDurable(true);
            messageEncoder.setHeader(header);
            messageEncoder.addData("foo");
            final Disposition receivedDisposition = transport.newInteraction()
                                                             .negotiateProtocol().consumeResponse()
                                                             .open().consumeResponse(Open.class)
                                                             .begin().consumeResponse(Begin.class)
                                                             .attachRole(Role.SENDER)
                                                             .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                             .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                                                             .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL,
                                                                                   Rejected.REJECTED_SYMBOL)
                                                             .attach().consumeResponse(Attach.class)
                                                             .consumeResponse(Flow.class)
                                                             .transferPayload(messageEncoder.getPayload())
                                                             .transferRcvSettleMode(ReceiverSettleMode.FIRST)
                                                             .transfer()
                                                             .consumeResponse()
                                                             .getLatestResponse(Disposition.class);

            assertThat(receivedDisposition.getSettled(), is(true));
            assertThat(receivedDisposition.getState(), is(instanceOf(Outcome.class)));
            if (getBrokerAdmin().supportsRestart())
            {
                assertThat(((Outcome) receivedDisposition.getState()).getSymbol(), is(Accepted.ACCEPTED_SYMBOL));
            }
            else
            {
                assertThat(((Outcome) receivedDisposition.getState()).getSymbol(), is(Rejected.REJECTED_SYMBOL));
            }
        }
    }

    @Test
    @SpecificationTest(section = "3.2.1",
            description = "Durable messages MUST NOT be lost even if an intermediary is unexpectedly terminated and "
                          + "restarted. A target which is not capable of fulfilling this guarantee MUST NOT accept messages "
                          + "where the durable header is set to true: if the source allows the rejected outcome then the "
                          + "message SHOULD be rejected with the precondition-failed error, otherwise the link MUST be "
                          + "detached by the receiver with the same error.")
    public void durableTransferWithoutRejectedOutcome() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            MessageEncoder messageEncoder = new MessageEncoder();
            final Header header = new Header();
            header.setDurable(true);
            messageEncoder.setHeader(header);
            messageEncoder.addData("foo");
            final Response<?> response = transport.newInteraction()
                                                  .negotiateProtocol().consumeResponse()
                                                  .open().consumeResponse(Open.class)
                                                  .begin().consumeResponse(Begin.class)
                                                  .attachRole(Role.SENDER)
                                                  .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                  .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                                                  .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL)
                                                  .attach().consumeResponse(Attach.class)
                                                  .consumeResponse(Flow.class)
                                                  .transferPayload(messageEncoder.getPayload())
                                                  .transferRcvSettleMode(ReceiverSettleMode.FIRST)
                                                  .transfer()
                                                  .consumeResponse()
                                                  .getLatestResponse();

            if (getBrokerAdmin().supportsRestart())
            {
                assertThat(response, is(notNullValue()));
                assertThat(response.getBody(), is(instanceOf(Disposition.class)));
                final Disposition receivedDisposition = (Disposition) response.getBody();
                assertThat(receivedDisposition.getSettled(), is(true));
                assertThat(receivedDisposition.getState(), is(instanceOf(Outcome.class)));
                assertThat(((Outcome) receivedDisposition.getState()).getSymbol(), is(Accepted.ACCEPTED_SYMBOL));
            }
            else
            {
                assertThat(response, is(notNullValue()));
                assertThat(response.getBody(), is(instanceOf(Detach.class)));
                final Detach receivedDetach = (Detach) response.getBody();
                assertThat(receivedDetach.getError(), is(notNullValue()));
                assertThat(receivedDetach.getError().getCondition(), is(AmqpError.PRECONDITION_FAILED));
            }
        }
    }

    @Test
    @SpecificationTest(section = "2.6.12", description = "Transferring A Message.")
    public void receiveTransferUnsettled() throws Exception
    {
        getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, TEST_MESSAGE_DATA);

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction()
                                                     .negotiateProtocol().consumeResponse()
                                                     .open().consumeResponse()
                                                     .begin().consumeResponse()
                                                     .attachRole(Role.RECEIVER)
                                                     .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                     .attach().consumeResponse()
                                                     .flowIncomingWindow(UnsignedInteger.ONE)
                                                     .flowNextIncomingId(UnsignedInteger.ZERO)
                                                     .flowOutgoingWindow(UnsignedInteger.ZERO)
                                                     .flowNextOutgoingId(UnsignedInteger.ZERO)
                                                     .flowLinkCredit(UnsignedInteger.ONE)
                                                     .flowHandleFromLinkHandle()
                                                     .flow();

            MessageDecoder messageDecoder = new MessageDecoder();
            boolean hasMore;
            do
            {
                Transfer responseTransfer = interaction.consumeResponse().getLatestResponse(Transfer.class);
                messageDecoder.addTransfer(responseTransfer);
                hasMore = Boolean.TRUE.equals(responseTransfer.getMore());
            }
            while (hasMore);

            Object data = messageDecoder.getData();
            assertThat(data, Is.is(CoreMatchers.equalTo(TEST_MESSAGE_DATA)));
        }
    }

    @Test
    @SpecificationTest(section = "2.6.12", description = "Transferring A Message.")
    public void receiveTransferReceiverSettleFirst() throws Exception
    {
        getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, TEST_MESSAGE_DATA);

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction()
                                                     .negotiateProtocol().consumeResponse()
                                                     .open().consumeResponse()
                                                     .begin().consumeResponse()
                                                     .attachRole(Role.RECEIVER)
                                                     .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                     .attachRcvSettleMode(ReceiverSettleMode.FIRST)
                                                     .attach().consumeResponse()
                                                     .flowIncomingWindow(UnsignedInteger.ONE)
                                                     .flowNextIncomingId(UnsignedInteger.ZERO)
                                                     .flowOutgoingWindow(UnsignedInteger.ZERO)
                                                     .flowNextOutgoingId(UnsignedInteger.ZERO)
                                                     .flowLinkCredit(UnsignedInteger.ONE)
                                                     .flowHandleFromLinkHandle()
                                                     .flow()
                                                     .receiveDelivery()
                                                     .decodeLatestDelivery();

            Object data = interaction.getDecodedLatestDelivery();
            assertThat(data, Is.is(CoreMatchers.equalTo(TEST_MESSAGE_DATA)));

            interaction.dispositionSettled(true)
                       .dispositionRole(Role.RECEIVER)
                       .disposition();

            // verify that no unexpected performative is received by closing
            interaction.doCloseConnection();
        }
    }

    @Test
    @SpecificationTest(section = "2.6.12", description = "Transferring A Message.")
    public void receiveTransferReceiverSettleSecond() throws Exception
    {
        getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, TEST_MESSAGE_DATA);

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction()
                                                     .negotiateProtocol().consumeResponse()
                                                     .open().consumeResponse()
                                                     .begin().consumeResponse()
                                                     .attachRole(Role.RECEIVER)
                                                     .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                     .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                                                     .attach().consumeResponse()
                                                     .flowIncomingWindow(UnsignedInteger.ONE)
                                                     .flowNextIncomingId(UnsignedInteger.ZERO)
                                                     .flowOutgoingWindow(UnsignedInteger.ZERO)
                                                     .flowNextOutgoingId(UnsignedInteger.ZERO)
                                                     .flowLinkCredit(UnsignedInteger.ONE)
                                                     .flowHandleFromLinkHandle()
                                                     .flow()
                                                     .receiveDelivery()
                                                     .decodeLatestDelivery();

            Object data = interaction.getDecodedLatestDelivery();
            assertThat(data, Is.is(CoreMatchers.equalTo(TEST_MESSAGE_DATA)));

            Disposition disposition = interaction.dispositionSettled(false)
                                                 .dispositionRole(Role.RECEIVER)
                                                 .dispositionState(new Accepted())
                                                 .disposition()
                                                 .consumeResponse(Disposition.class)
                                                 .getLatestResponse(Disposition.class);
            assertThat(disposition.getSettled(), is(true));

            interaction.consumeResponse(null, Flow.class);

        }
    }

    @Test
    @SpecificationTest(section = "2.6.12", description = "Transferring A Message.")
    public void receiveTransferReceiverSettleSecondWithRejectedOutcome() throws Exception
    {
        getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, TEST_MESSAGE_DATA);

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction()
                                                     .negotiateProtocol().consumeResponse()
                                                     .open().consumeResponse()
                                                     .begin().consumeResponse()
                                                     .attachRole(Role.RECEIVER)
                                                     .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                     .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL, Rejected.REJECTED_SYMBOL)
                                                     .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                                                     .attach().consumeResponse()
                                                     .flowIncomingWindow(UnsignedInteger.ONE)
                                                     .flowNextIncomingId(UnsignedInteger.ZERO)
                                                     .flowOutgoingWindow(UnsignedInteger.ZERO)
                                                     .flowNextOutgoingId(UnsignedInteger.ZERO)
                                                     .flowLinkCredit(UnsignedInteger.ONE)
                                                     .flowHandleFromLinkHandle()
                                                     .flow();

            Object data = interaction.receiveDelivery().decodeLatestDelivery().getDecodedLatestDelivery();
            assertThat(data, is(equalTo(TEST_MESSAGE_DATA)));

            interaction.dispositionSettled(false)
                       .dispositionRole(Role.RECEIVER)
                       .dispositionState(new Rejected())
                       .disposition()
                       .consumeResponse(Disposition.class, Flow.class);
            Response<?> response = interaction.getLatestResponse();
            if (response.getBody() instanceof Flow)
            {
                interaction.consumeResponse(Disposition.class);
            }

            Disposition disposition = interaction.getLatestResponse(Disposition.class);
            assertThat(disposition.getSettled(), is(true));

            interaction.consumeResponse(null, Flow.class);

        }
    }

    @Ignore
    @Test
    @SpecificationTest(section = "2.6.12", description = "Transferring A Message.")
    public void receiveTransferReceiverSettleSecondWithImplicitDispositionState() throws Exception
    {
        getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, TEST_MESSAGE_DATA);

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction()
                                                     .negotiateProtocol().consumeResponse()
                                                     .open().consumeResponse()
                                                     .begin().consumeResponse()
                                                     .attachRole(Role.RECEIVER)
                                                     .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                     .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                                                     .attachSourceOutcomes()
                                                     .attachSourceDefaultOutcome(null)
                                                     .attach().consumeResponse()
                                                     .flowIncomingWindow(UnsignedInteger.ONE)
                                                     .flowNextIncomingId(UnsignedInteger.ZERO)
                                                     .flowOutgoingWindow(UnsignedInteger.ZERO)
                                                     .flowNextOutgoingId(UnsignedInteger.ZERO)
                                                     .flowLinkCredit(UnsignedInteger.ONE)
                                                     .flowHandleFromLinkHandle()
                                                     .flow()
                                                     .receiveDelivery()
                                                     .decodeLatestDelivery();

            Object data = interaction.getDecodedLatestDelivery();
            assertThat(data, Is.is(CoreMatchers.equalTo(TEST_MESSAGE_DATA)));

            Disposition disposition = interaction.dispositionSettled(false)
                                                 .dispositionRole(Role.RECEIVER)
                                                 .dispositionState(null)
                                                 .disposition()
                                                 .consumeResponse(Disposition.class)
                                                 .getLatestResponse(Disposition.class);
            assertThat(disposition.getSettled(), is(true));

            interaction.consumeResponse(null, Flow.class);

        }
    }

    @Test
    @SpecificationTest(section = "2.6.12", description = "[...] the receiving application MAY wish to indicate"
                                                         + " non-terminal delivery states to the sender")
    public void receiveTransferReceiverIndicatesNonTerminalDeliveryState() throws Exception
    {

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction();

            Open open = interaction.negotiateProtocol().consumeResponse()
                                   .openMaxFrameSize(UnsignedInteger.valueOf(4096))
                                   .open().consumeResponse()
                                   .getLatestResponse(Open.class);

            int negotiatedFrameSize = open.getMaxFrameSize().intValue();
            String testMessageData = Stream.generate(() -> "*").limit(negotiatedFrameSize).collect(Collectors.joining());

            getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, testMessageData);

            interaction.begin().consumeResponse()
                       .attachRole(Role.RECEIVER)
                       .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                       .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                       .attach().consumeResponse()
                       .flowIncomingWindow(UnsignedInteger.ONE)
                       .flowNextIncomingId(UnsignedInteger.ZERO)
                       .flowOutgoingWindow(UnsignedInteger.ZERO)
                       .flowNextOutgoingId(UnsignedInteger.ZERO)
                       .flowLinkCredit(UnsignedInteger.ONE)
                       .flowHandleFromLinkHandle()
                       .flow()
                       .sync();

            MessageDecoder messageDecoder = new MessageDecoder();

            Transfer first = interaction.consumeResponse(Transfer.class)
                                        .getLatestResponse(Transfer.class);
            assertThat(first.getMore(), is(equalTo(true)));
            messageDecoder.addTransfer(first);

            final long firstRemaining;
            try (QpidByteBuffer payload = first.getPayload())
            {
                firstRemaining = payload.remaining();
            }

            Received state = new Received();
            state.setSectionNumber(UnsignedInteger.ZERO);
            state.setSectionOffset(UnsignedLong.valueOf(firstRemaining + 1));

            interaction.dispositionSettled(false)
                       .dispositionRole(Role.RECEIVER)
                       .dispositionState(state)
                       .disposition()
                       .sync();

            Transfer second = interaction.consumeResponse(Transfer.class)
                                         .getLatestResponse(Transfer.class);
            assertThat(second.getMore(), isOneOf(false, null));
            messageDecoder.addTransfer(second);

            assertThat(messageDecoder.getData(), is(equalTo(testMessageData)));

            Disposition disposition = interaction.dispositionSettled(false)
                                                 .dispositionRole(Role.RECEIVER)
                                                 .dispositionState(new Accepted())
                                                 .disposition().consumeResponse(Disposition.class)
                                                 .getLatestResponse(Disposition.class);
            assertThat(disposition.getSettled(), is(true));

            interaction.consumeResponse(null, Flow.class);
        }
    }

    @Test
    @SpecificationTest(section = "2.7.3", description = "The sender SHOULD respect the receiver’s desired settlement mode if"
                                                        + " the receiver initiates the attach exchange and the sender supports the desired mode.")
    public void receiveTransferSenderSettleModeSettled() throws Exception
    {
        getBrokerAdmin().putMessageOnQueue(BrokerAdmin.TEST_QUEUE_NAME, TEST_MESSAGE_DATA);

        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = transport.newInteraction()
                                                     .negotiateProtocol().consumeResponse()
                                                     .open().consumeResponse()
                                                     .begin().consumeResponse()
                                                     .attachRole(Role.RECEIVER)
                                                     .attachSourceAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                                     .attachRcvSettleMode(ReceiverSettleMode.FIRST)
                                                     .attachSndSettleMode(SenderSettleMode.SETTLED)
                                                     .attach().consumeResponse(Attach.class);
            Attach attach = interaction.getLatestResponse(Attach.class);
            assumeThat(attach.getSndSettleMode(), is(equalTo(SenderSettleMode.SETTLED)));

            interaction.flowIncomingWindow(UnsignedInteger.ONE)
                                                     .flowNextIncomingId(UnsignedInteger.ZERO)
                                                     .flowOutgoingWindow(UnsignedInteger.ZERO)
                                                     .flowNextOutgoingId(UnsignedInteger.ZERO)
                                                     .flowLinkCredit(UnsignedInteger.ONE)
                                                     .flowHandleFromLinkHandle()
                                                     .flow();

            List<Transfer> transfers = interaction.receiveDelivery().getLatestDelivery();
            final AtomicBoolean isSettled = new AtomicBoolean();
            transfers.forEach(transfer -> { if (Boolean.TRUE.equals(transfer.getSettled())) { isSettled.set(true);}});

            assertThat(isSettled.get(), is(true));

            // verify no unexpected performative received by closing the connection
            interaction.doCloseConnection();
        }
    }

    @Test
    @SpecificationTest(section = "2.7.5",
            description = "[delivery-tag] uniquely identifies the delivery attempt for a given message on this link.")
    public void transfersWithDuplicateUnsettledDeliveryTag() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Binary deliveryTag = new Binary("testDeliveryTag".getBytes(UTF_8));

            Interaction interaction = transport.newInteraction();
            interaction.negotiateProtocol()
                       .consumeResponse()
                       .open()
                       .consumeResponse(Open.class)
                       .begin()
                       .consumeResponse(Begin.class)
                       .attachRole(Role.SENDER)
                       .attachRcvSettleMode(ReceiverSettleMode.SECOND)
                       .attach()
                       .consumeResponse(Attach.class)
                       .consumeResponse(Flow.class);

            Flow flow = interaction.getLatestResponse(Flow.class);
            assertThat(flow.getLinkCredit().intValue(), is(greaterThan(1)));

            interaction.transferDeliveryId(UnsignedInteger.ZERO)
                       .transferDeliveryTag(deliveryTag)
                       .transferPayloadData("test")
                       .transfer()
                       .sync()
                       .transferDeliveryTag(deliveryTag)
                       .transferDeliveryId(UnsignedInteger.ONE)
                       .transferPayloadData("test2")
                       .transfer()
                       .sync();

            do
            {
                interaction.consumeResponse();
                Response<?> response = interaction.getLatestResponse();
                assertThat(response, is(notNullValue()));

                Object body = response.getBody();
                if (body instanceof ErrorCarryingFrameBody)
                {
                    Error error = ((ErrorCarryingFrameBody) body).getError();
                    assertThat(error, is(notNullValue()));
                    break;
                }
                else if (body instanceof Disposition)
                {
                    Disposition disposition = (Disposition) body;
                    assertThat(disposition.getSettled(), is(equalTo(false)));
                    assertThat(disposition.getFirst(), is(not(equalTo(UnsignedInteger.ONE))));
                    assertThat(disposition.getLast(), is(not(equalTo(UnsignedInteger.ONE))));
                }
                else if (!(body instanceof Flow))
                {
                    fail("Unexpected response " + body);
                }
            } while (true);
        }
    }

    @Test
    @SpecificationTest(section = "2.6.12",
            description = "The delivery-tag MUST be unique amongst all deliveries that"
                          + " could be considered unsettled by either end of the link.")
    public void deliveryTagCanBeReusedAfterDeliveryIsSettled() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Binary deliveryTag = new Binary("testDeliveryTag".getBytes(UTF_8));

            Interaction interaction = transport.newInteraction();
            interaction.negotiateProtocol()
                       .consumeResponse()
                       .open()
                       .consumeResponse(Open.class)
                       .begin()
                       .consumeResponse(Begin.class)
                       .attachRole(Role.SENDER)
                       .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                       .attach()
                       .consumeResponse(Attach.class)
                       .consumeResponse(Flow.class);

            Flow flow = interaction.getLatestResponse(Flow.class);
            assertThat(flow.getLinkCredit().intValue(), is(greaterThan(1)));

            interaction.transferDeliveryId(UnsignedInteger.ZERO)
                       .transferDeliveryTag(deliveryTag)
                       .transferPayloadData("test")
                       .transferSettled(true)
                       .transfer()
                       .sync()

                       .transferDeliveryTag(deliveryTag)
                       .transferDeliveryId(UnsignedInteger.ONE)
                       .transferPayloadData("test2")
                       .transfer()
                       .sync();

            interaction.doCloseConnection();

            assumeThat(getBrokerAdmin().isQueueDepthSupported(), is(true));
            assertThat(getBrokerAdmin().getQueueDepthMessages(BrokerAdmin.TEST_QUEUE_NAME), is(equalTo(2)));
        }
    }

    @Test
    @SpecificationTest(section = "2.7.3",
            description = "max-message-size: This field indicates the maximum message size supported by the link"
                          + " endpoint. Any attempt to deliver a message larger than this results in a"
                          + " message-size-exceeded link-error. If this field is zero or unset, there is no maximum"
                          + " size imposed by the link endpoint.")
    public void exceedMaxMessageSizeLimit() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Binary deliveryTag = new Binary("testDeliveryTag".getBytes(UTF_8));

            Interaction interaction = transport.newInteraction();
            Open open = interaction.negotiateProtocol().consumeResponse()
                                   .open().consumeResponse(Open.class)
                                   .getLatestResponse(Open.class);

            long maxFrameSize = open.getMaxFrameSize() == null ? Integer.MAX_VALUE : open.getMaxFrameSize().longValue();

            Attach attach = interaction.begin().consumeResponse(Begin.class)
                                       .attachRole(Role.SENDER)
                                       .attachTargetAddress(BrokerAdmin.TEST_QUEUE_NAME)
                                       .attach().consumeResponse(Attach.class)
                                       .getLatestResponse(Attach.class);

            final UnsignedLong maxMessageSizeLimit = attach.getMaxMessageSize();
            assumeThat(maxMessageSizeLimit, is(notNullValue()));
            assumeThat(maxMessageSizeLimit.longValue(),
                       is(both(greaterThan(0L)).and(lessThan(MAX_MAX_MESSAGE_SIZE_WE_ARE_WILLING_TO_TEST))));

            Flow flow = interaction.consumeResponse(Flow.class)
                                   .getLatestResponse(Flow.class);
            assertThat(flow.getLinkCredit().intValue(), is(greaterThan(1)));

            final long chunkSize = Math.min(1024 * 1024, maxFrameSize - 100);
            byte[] payloadChunk = createTestPaload(chunkSize);
            interaction.transferDeliveryId(UnsignedInteger.ZERO)
                       .transferDeliveryTag(deliveryTag)
                       .transferPayloadData(payloadChunk)
                       .transferSettled(true)
                       .transferMore(true);
            int payloadSize = 0;
            while (payloadSize < maxMessageSizeLimit.longValue())
            {
                payloadSize += chunkSize;
                interaction.transfer();
            }

            while (true)
            {
                Response<?> response = interaction.consumeResponse(Flow.class, Disposition.class, Detach.class).getLatestResponse();
                if (response != null)
                {
                    if (response.getBody() instanceof Detach)
                    {
                        break;
                    }
                    else if (response.getBody() instanceof Disposition)
                    {
                        assertThat(((Disposition) response.getBody()).getState(), is(instanceOf(Rejected.class)));
                        assertThat(((Rejected) ((Disposition) response.getBody()).getState()).getError(), is(notNullValue()));
                        assertThat(((Rejected) ((Disposition) response.getBody()).getState()).getError().getCondition(), is(equalTo(LinkError.MESSAGE_SIZE_EXCEEDED)));
                    }
                }
            }
            Detach detach = interaction.getLatestResponse(Detach.class);

            assertThat(detach.getError(), is(notNullValue()));
            assertThat(detach.getError().getCondition(), is(equalTo(LinkError.MESSAGE_SIZE_EXCEEDED)));
        }
    }

    private byte[] createTestPaload(final long payloadSize)
    {
        if (payloadSize > 1024*1024*1024)
        {
            throw new IllegalArgumentException(String.format("Payload size (%.2f MB) too big", payloadSize / (1024. * 1024.)));
        }
        return new byte[(int) payloadSize];
    }
}

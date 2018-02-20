/*
 *
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
package org.apache.qpid.test.unit.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.TextMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.client.RejectBehaviour;
import org.apache.qpid.configuration.ClientProperties;
import org.apache.qpid.server.model.AlternateBinding;
import org.apache.qpid.test.utils.QpidBrokerTestCase;


/**
 * Test that the MaxRedelivery feature works as expected, allowing the client to reject
 * messages during rollback/recover whilst specifying they not be requeued if delivery
 * to an application has been attempted a specified number of times.
 *
 * General approach: specify a set of messages which will cause the test client to then
 * deliberately rollback/recover the session after consuming, and monitor that they are
 * re-delivered the specified number of times before the client rejects them without requeue
 * and then verify that they are not subsequently redelivered.
 *
 * Additionally, the queue used in the test is configured for DLQ'ing, and the test verifies
 * that the messages rejected without requeue are then present on the appropriate DLQ.
 */
public class MaxDeliveryCountTest extends QpidBrokerTestCase
{
    private static final Logger LOGGER = LoggerFactory.getLogger(MaxDeliveryCountTest.class);
    private boolean _failed;
    private String _failMsg;
    private static final int MSG_COUNT = 15;
    private static final int MAX_DELIVERY_COUNT = 2;
    private static final String JMSX_DELIVERY_COUNT = "JMSXDeliveryCount";
    private CountDownLatch _awaitCompletion;

    private long _awaitEmptyQueue;
    private long _awaitCompletionTimeout = 20;

    /** index numbers of messages to be redelivered */
    private final List<Integer> _redeliverMsgs = Arrays.asList(1, 2, 5, 14);
    private String _testQueueName;
    private Queue _testDeadLetterQueue;
    private Queue _testQueue;

    @Override
    public void setUp() throws Exception
    {
        _awaitEmptyQueue = Long.parseLong(System.getProperty("MaxDeliveryCountTest.awaitEmptyQueue", "2500"));
        _awaitCompletionTimeout = Long.parseLong(System.getProperty("MaxDeliveryCountTest.awaitCompletionTimeout", "20000"));

        // Set client-side flag to allow the server to determine if messages
        // dead-lettered or requeued.
        if (!isBroker010())
        {
            setTestClientSystemProperty(ClientProperties.REJECT_BEHAVIOUR_PROP_NAME, RejectBehaviour.SERVER.toString());
        }
        super.setUp();
        _testQueueName = getTestQueueName();
        String testDeadLetterQueueName = _testQueueName + "_DLQ";

        Connection connection = getConnectionBuilder().setClientId("clientid").build();
        connection.start();
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);

        _testDeadLetterQueue = createTestQueue(session, testDeadLetterQueueName);

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(org.apache.qpid.server.model.Queue.NAME, _testQueueName);
        attributes.put(org.apache.qpid.server.model.Queue.MAXIMUM_DELIVERY_ATTEMPTS, MAX_DELIVERY_COUNT);
        attributes.put(org.apache.qpid.server.model.Queue.ALTERNATE_BINDING,
                       new ObjectMapper().writeValueAsString(Collections.singletonMap(AlternateBinding.DESTINATION,
                                                                                      testDeadLetterQueueName)));
        createEntityUsingAmqpManagement(_testQueueName,
                                        session,
                                        "org.apache.qpid.StandardQueue",
                                        attributes);
        _testQueue = getQueueFromName(session, _testQueueName);


        MessageProducer producer = session.createProducer(_testQueue);
        for (int count = 1; count <= MSG_COUNT; count++)
        {
            Message msg = session.createTextMessage(generateContent(count));
            msg.setIntProperty("count", count);
            producer.send(msg);
        }
        session.commit();

        connection.close();

        _failed = false;
        _awaitCompletion = new CountDownLatch(1);
    }

    private String generateContent(int count)
    {
        return "Message " + count + " content.";
    }

    public void testAsynchronousClientAckSession() throws Exception
    {
        doTest(Session.CLIENT_ACKNOWLEDGE, _redeliverMsgs, false);
    }

    public void testAsynchronousTransactedSession() throws Exception
    {
        doTest(Session.SESSION_TRANSACTED, _redeliverMsgs, false);
    }

    public void testAsynchronousAutoAckSession() throws Exception
    {
        doTest(Session.AUTO_ACKNOWLEDGE, _redeliverMsgs, false);
    }

    public void testAsynchronousDupsOkSession() throws Exception
    {
        doTest(Session.DUPS_OK_ACKNOWLEDGE, _redeliverMsgs, false);
    }

    public void testSynchronousClientAckSession() throws Exception
    {
        doTest(Session.CLIENT_ACKNOWLEDGE, _redeliverMsgs, true);
    }

    public void testSynchronousTransactedSession() throws Exception
    {
        doTest(Session.SESSION_TRANSACTED, _redeliverMsgs, true);
    }

    public void testWhenBrokerIsRestartedAfterEnqeuingMessages() throws Exception
    {
        restartDefaultBroker();

        doTest(Session.SESSION_TRANSACTED, _redeliverMsgs, true);
    }

    public void testBrowsingDoesNotIncrementDeliveryCount() throws Exception
    {
        Connection connection = getConnection();
        try
        {
            connection.start();
            final Session session = connection.createSession(true, Session.SESSION_TRANSACTED);

            final Map<String, Object> messageInfoBefore = getMessageInfo(_testQueueName, 0);
            assertEquals("Unexpected delivery count before browse", 0, messageInfoBefore.get("deliveryCount"));

            browseQueueAndValidationDeliveryHeaders(session, _testQueue);

            final Map<String, Object> messageInfoAfter = getMessageInfo(_testQueueName, 0);
            assertEquals("Unexpected delivery count after first browse", 0, messageInfoAfter.get("deliveryCount"));

            browseQueueAndValidationDeliveryHeaders(session, _testQueue);

            final Map<String, Object> messageInfoAfterSecondBrowse = getMessageInfo(_testQueueName, 0);
            assertEquals("Unexpected delivery count after second browse",
                         0,
                         messageInfoAfterSecondBrowse.get("deliveryCount"));

            browseQueueAndValidationDeliveryHeaders(session, _testQueue);
        }
        finally
        {
            connection.close();
        }
    }

    private Map<String, Object> getMessageInfo(String queueName, final int index) throws Exception
    {
        List<Map<String, Object>> messages;
        Connection connection = getConnection();
        try
        {
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            messages = (List<Map<String, Object>>) performOperationUsingAmqpManagement(queueName,
                                                                                       "getMessageInfo",
                                                                                       session,
                                                                                       "org.apache.qpid.Queue",
                                                                                       Collections.emptyMap());
        }
        finally
        {
            connection.close();
        }
        assertTrue("Too few messsages on the queue: " + messages.size(), messages.size()>index);
        return messages.get(index);
    }

    private void browseQueueAndValidationDeliveryHeaders(final Session session, final Queue queue) throws Exception
    {
        final QueueBrowser browser = session.createBrowser(queue);
        @SuppressWarnings("unchecked")
        final List<Message> messages = (List<Message>) new ArrayList(Collections.list(browser.getEnumeration()));
        assertEquals("Unexpected number of messages seen by browser", MSG_COUNT, messages.size());
        for (Message browsedMessage: messages)
        {
            assertFalse(browsedMessage.getJMSRedelivered());

            if (browsedMessage.propertyExists(JMSX_DELIVERY_COUNT))
            {
                assertEquals(1, browsedMessage.getIntProperty(JMSX_DELIVERY_COUNT));
            }
        }
        browser.close();
    }

    private void doTest(final int deliveryMode,
                        final List<Integer> redeliverMsgs,
                        final boolean synchronous) throws Exception
    {
        final Connection clientConnection = getConnectionBuilder().setClientId("clientid").build();

        final boolean transacted = deliveryMode == Session.SESSION_TRANSACTED;
        final Session clientSession = clientConnection.createSession(transacted, deliveryMode);

        MessageConsumer consumer = clientSession.createConsumer(_testQueue);

        clientConnection.start();
        assertEquals("The queue should have " + MSG_COUNT + " msgs at start",
                MSG_COUNT, getQueueDepth(clientConnection, _testQueue));


        int expectedDeliveries = MSG_COUNT + ((MAX_DELIVERY_COUNT -1) * redeliverMsgs.size());

        if(synchronous)
        {
            doSynchronousTest(clientSession, consumer, clientSession.getAcknowledgeMode(),
                    MAX_DELIVERY_COUNT, expectedDeliveries, redeliverMsgs);
        }
        else
        {
            addMessageListener(clientSession, consumer, clientSession.getAcknowledgeMode(),
                    MAX_DELIVERY_COUNT, expectedDeliveries, redeliverMsgs);

            try
            {
                if (!_awaitCompletion.await(_awaitCompletionTimeout, TimeUnit.MILLISECONDS))
                {
                    fail("Test did not complete in 20 seconds.");
                }
            }
            catch (InterruptedException e)
            {
                fail("Unable to wait for test completion");
                throw e;
            }

            if(_failed)
            {
                fail(_failMsg);
            }

        }
        consumer.close();

        // In the non-transaction case, control may return to the client before the messaging transaction is committed.
        if (clientSession.getAcknowledgeMode() != Session.SESSION_TRANSACTED
            && clientSession.getAcknowledgeMode() != Session.CLIENT_ACKNOWLEDGE)
        {
            final long timeout = System.currentTimeMillis() + _awaitEmptyQueue;
            while(getQueueDepth(clientConnection, _testQueue) > 0 && System.currentTimeMillis() < timeout)
            {
                Thread.sleep(100);
            }
        }

        //check the source queue is now empty
        assertEquals("The queue should have 0 msgs left", 0, getQueueDepth(clientConnection, _testQueue));

        //check the DLQ has the required number of rejected-without-requeue messages
        verifyDLQdepth(redeliverMsgs.size(), clientConnection);

        if (!isBroker10())
        {
            if (isBrokerStorePersistent())
            {
                //restart the broker to verify persistence of the DLQ and the messages on it
                clientConnection.close();

                restartDefaultBroker();

                final Connection clientConnection2 = getConnection();
                clientConnection2.start();

                //verify the messages on the DLQ
                verifyDLQcontent(clientConnection2, redeliverMsgs);
                clientConnection2.close();
            }
            else
            {

                //verify the messages on the DLQ
                verifyDLQcontent(clientConnection, redeliverMsgs);
                clientConnection.close();
            }
        }

    }

    private void verifyDLQdepth(int expectedQueueDepth, final Connection clientConnection) throws Exception
    {
        assertEquals("The DLQ should have " + expectedQueueDepth + " msgs on it",
                     expectedQueueDepth,
                     getQueueDepth(clientConnection, _testDeadLetterQueue));
    }


    private void verifyDLQcontent(Connection clientConnection, List<Integer> redeliverMsgs) throws JMSException
    {
        Session clientSession = clientConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        MessageConsumer consumer = clientSession.createConsumer(_testDeadLetterQueue);

        //keep track of the message we expect to still be on the DLQ
        List<Integer> outstandingMessages = new ArrayList<>(redeliverMsgs);
        int numMsg = outstandingMessages.size();

        for(int i = 0; i < numMsg; i++)
        {
            Message message = consumer.receive(getReceiveTimeout());

            assertNotNull("failed to consume expected message " + i + " from DLQ", message);
            assertTrue("message " + i + " was the wrong type", message instanceof TextMessage);

            //using Integer here to allow removing the value from the list, using int
            //would instead result in removal of the element at that index
            Integer msgId = message.getIntProperty("count");

            TextMessage txt = (TextMessage) message;
            LOGGER.info("Received message " + msgId + " at " + i + " from the DLQ: " + txt.getText());

            assertTrue("message " + i + " was not one of those which should have been on the DLQ",
                    redeliverMsgs.contains(msgId));
            assertTrue("message " + i + " was not one of those expected to still be on the DLQ",
                    outstandingMessages.contains(msgId));
            assertEquals("Message " + i + " content was not as expected", generateContent(msgId), txt.getText());

            //remove from the list of outstanding msgs
            outstandingMessages.remove(msgId);
        }

        if(outstandingMessages.size() > 0)
        {
            String failures = "";
            for(Integer msg : outstandingMessages)
            {
                failures = failures.concat(msg + " ");
            }
            fail("some DLQ'd messages were not found on the DLQ: " + failures);
        }
    }

    private void addMessageListener(final Session session, final MessageConsumer consumer, final int deliveryMode, final int maxDeliveryCount,
                                    final int expectedTotalNumberOfDeliveries, final List<Integer> redeliverMsgs) throws JMSException
    {
        if(deliveryMode == org.apache.qpid.jms.Session.NO_ACKNOWLEDGE
                || deliveryMode == org.apache.qpid.jms.Session.PRE_ACKNOWLEDGE)
        {
            failAsyncTest("Max Delivery feature is not supported with this acknowledgement mode" +
                        "when using asynchronous message delivery.");
        }

        consumer.setMessageListener(new MessageListener()
        {
            private int _deliveryAttempts = 0; //number of times given message(s) have been seen
            private int _numMsgsToBeRedelivered = 0; //number of messages to rollback/recover
            private int _totalNumDeliveries = 0;
            private int _expectedMessage = 1;

            @Override
            public void onMessage(Message message)
            {
                if(_failed || _awaitCompletion.getCount() == 0L)
                {
                    //don't process anything else
                    return;
                }

                _totalNumDeliveries++;

                if (message == null)
                {
                    failAsyncTest("Should not get null messages");
                    return;
                }

                try
                {
                    int msgId = message.getIntProperty("count");

                    LOGGER.info("Received message: " + msgId);

                    //check the message is the one we expected
                    if(_expectedMessage != msgId)
                    {
                        failAsyncTest("Expected message " + _expectedMessage + " , got message " + msgId);
                        return;
                    }

                    _expectedMessage++;

                    //keep track of the overall deliveries to ensure we don't see more than expected
                    if(_totalNumDeliveries > expectedTotalNumberOfDeliveries)
                    {
                        failAsyncTest("Expected total of " + expectedTotalNumberOfDeliveries +
                                " message deliveries, reached " + _totalNumDeliveries);
                    }

                    //check if this message is one chosen to be rolled back / recovered
                    if(redeliverMsgs.contains(msgId))
                    {
                        _numMsgsToBeRedelivered++;

                        //check if next message is going to be rolled back / recovered too
                        if(redeliverMsgs.contains(msgId +1))
                        {
                            switch(deliveryMode)
                            {
                                case Session.SESSION_TRANSACTED:
                                    //skip on to next message immediately
                                    return;
                                case Session.CLIENT_ACKNOWLEDGE:
                                    //skip on to next message immediately
                                    return;
                                case Session.DUPS_OK_ACKNOWLEDGE:
                                    //fall through
                                case Session.AUTO_ACKNOWLEDGE:
                                    //must recover session now or onMessage will ack, so
                                    //just fall through the if
                                    break;
                            }
                        }

                        _deliveryAttempts++; //increment count of times the current rolled back/recovered message(s) have been seen

                        LOGGER.debug("ROLLBACK/RECOVER");
                        switch(deliveryMode)
                        {
                            case Session.SESSION_TRANSACTED:
                                session.rollback();
                                break;
                            case Session.CLIENT_ACKNOWLEDGE:
                                //fall through
                            case Session.DUPS_OK_ACKNOWLEDGE:
                                //fall through
                            case Session.AUTO_ACKNOWLEDGE:
                                session.recover();
                                break;
                        }

                        if( _deliveryAttempts >= maxDeliveryCount)
                        {
                            //the client should have rejected the latest messages upon then
                            //above recover/rollback, adjust counts to compensate
                            _deliveryAttempts = 0;
                        }
                        else
                        {
                            //the message(s) should be redelivered, adjust expected message
                            _expectedMessage -= _numMsgsToBeRedelivered;
                        }
                        LOGGER.debug("XXX _expectedMessage: " + _expectedMessage + " _deliveryAttempts : " + _deliveryAttempts + " _numMsgsToBeRedelivered=" + _numMsgsToBeRedelivered);
                        //reset count of messages expected to be redelivered
                        _numMsgsToBeRedelivered = 0;
                    }
                    else
                    {
                        //consume the message
                        switch(deliveryMode)
                        {
                            case Session.SESSION_TRANSACTED:
                                session.commit();
                                break;
                            case Session.CLIENT_ACKNOWLEDGE:
                                message.acknowledge();
                                break;
                            case Session.DUPS_OK_ACKNOWLEDGE:
                                //fall-through
                            case Session.AUTO_ACKNOWLEDGE:
                                //do nothing, onMessage will ack on exit.
                                break;
                        }
                    }

                    if (msgId == MSG_COUNT)
                    {
                        //if this is the last message let the test complete.
                        if (expectedTotalNumberOfDeliveries == _totalNumDeliveries)
                        {
                            _awaitCompletion.countDown();
                        }
                        else
                        {
                            failAsyncTest("Last message received, but we have not had the " +
                                        "expected number of total delivieres. Received " + _totalNumDeliveries + " Expecting : " + expectedTotalNumberOfDeliveries);
                        }
                    }
                }
                catch (JMSException e)
                {
                    failAsyncTest(e.getMessage());
                }
            }
        });
    }

    private void failAsyncTest(String msg)
    {
        LOGGER.error("Failing test because: " + msg);
        _failMsg = msg;
        _failed = true;
        _awaitCompletion.countDown();
    }

    private void doSynchronousTest(final Session session, final MessageConsumer consumer, final int deliveryMode, final int maxDeliveryCount,
            final int expectedTotalNumberOfDeliveries, final List<Integer> redeliverMsgs) throws Exception
   {
        if(deliveryMode == Session.AUTO_ACKNOWLEDGE
                || deliveryMode == Session.DUPS_OK_ACKNOWLEDGE
                || deliveryMode == org.apache.qpid.jms.Session.PRE_ACKNOWLEDGE
                || deliveryMode == org.apache.qpid.jms.Session.NO_ACKNOWLEDGE)
        {
            fail("Max Delivery feature is not supported with this acknowledgement mode" +
                 "when using synchronous message delivery.");
        }

        int _deliveryAttempts = 0; //number of times given message(s) have been seen
        int _numMsgsToBeRedelivered = 0; //number of messages to rollback/recover
        int _totalNumDeliveries = 0;
        int _expectedMessage = 1;

        while(!_failed)
        {
            Message message = consumer.receive(getReceiveTimeout());

            _totalNumDeliveries++;

            if (message == null)
            {
                fail("Should not get null messages");
                return;
            }

            try
            {
                int msgId = message.getIntProperty("count");

                LOGGER.info("Received message: " + msgId);

                //check the message is the one we expected
                assertEquals("Unexpected message.", _expectedMessage, msgId);

                _expectedMessage++;

                //keep track of the overall deliveries to ensure we don't see more than expected
                assertTrue("Exceeded expected total number of deliveries.",
                        _totalNumDeliveries <= expectedTotalNumberOfDeliveries );

                //check if this message is one chosen to be rolled back / recovered
                if(redeliverMsgs.contains(msgId))
                {
                    //keep track of the number of messages we will have redelivered
                    //upon rollback/recover
                    _numMsgsToBeRedelivered++;

                    if(redeliverMsgs.contains(msgId +1))
                    {
                        //next message is going to be rolled back / recovered too.
                        //skip ahead to it
                        continue;
                    }

                    _deliveryAttempts++; //increment count of times the current rolled back/recovered message(s) have been seen

                    switch(deliveryMode)
                    {
                        case Session.SESSION_TRANSACTED:
                            session.rollback();
                            break;
                        case Session.CLIENT_ACKNOWLEDGE:
                            session.recover();
                            break;
                    }

                    if( _deliveryAttempts >= maxDeliveryCount)
                    {
                        //the client should have rejected the latest messages upon then
                        //above recover/rollback, adjust counts to compensate
                        _deliveryAttempts = 0;
                    }
                    else
                    {
                        //the message(s) should be redelivered, adjust expected message
                        _expectedMessage -= _numMsgsToBeRedelivered;
                    }

                    //As we just rolled back / recovered, we must reset the
                    //count of messages expected to be redelivered
                    _numMsgsToBeRedelivered = 0;
                }
                else
                {
                    //consume the message
                    switch(deliveryMode)
                    {
                        case Session.SESSION_TRANSACTED:
                            session.commit();
                            break;
                        case Session.CLIENT_ACKNOWLEDGE:
                            message.acknowledge();
                            break;
                    }
                }

                if (msgId == MSG_COUNT)
                {
                    //if this is the last message let the test complete.
                    assertTrue("Last message received, but we have not had the " +
                            "expected number of total delivieres",
                            expectedTotalNumberOfDeliveries == _totalNumDeliveries);

                    break;
                }
            }
            catch (JMSException e)
            {
                fail(e.getMessage());
            }
        }
   }
}

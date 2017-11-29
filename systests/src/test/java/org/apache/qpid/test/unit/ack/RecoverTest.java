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
package org.apache.qpid.test.unit.ack;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.configuration.ClientProperties;
import org.apache.qpid.test.utils.QpidBrokerTestCase;

/**
 * Legacy JMS client specific tests
 */
public class RecoverTest extends QpidBrokerTestCase
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RecoverTest.class);

    private static final int SENT_COUNT = 4;

    private volatile Exception _error;
    private long _timeout;
    private Connection _connection;
    private Session _consumerSession;
    private MessageConsumer _consumer;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _error = null;
        _timeout = getReceiveTimeout();
    }

    private void initTest() throws Exception
    {
        _connection = getConnection();

        _consumerSession = _connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Queue queue = createTestQueue(_consumerSession);

        _consumer = _consumerSession.createConsumer(queue);

        LOGGER.info("Sending four messages");
        sendMessage(_connection.createSession(false, Session.AUTO_ACKNOWLEDGE), queue, SENT_COUNT);
        LOGGER.info("Starting connection");
        _connection.start();
    }

    private Message validateNextMessages(int nextCount, int startIndex) throws JMSException
    {
        Message message = null;

        for (int index = 0; index < nextCount; index++)
        {
            message = _consumer.receive(_timeout);
            assertEquals(startIndex + index, message.getIntProperty(INDEX));
        }
        return message;
    }

    private void validateRemainingMessages(int remaining) throws JMSException
    {
        int index = SENT_COUNT - remaining;

        Message message = null;
        while (index != SENT_COUNT)
        {
            message =  _consumer.receive(_timeout);
            assertNotNull(message);
            int expected = index++;
            assertEquals("Message has unexpected index", expected, message.getIntProperty(INDEX));
        }

        if (message != null)
        {
            LOGGER.info("Received redelivery of three messages. Acknowledging last message");
            message.acknowledge();
        }

        LOGGER.info("Calling acknowledge with no outstanding messages");
        // all acked so no messages to be delivered
        _consumerSession.recover();

        message = _consumer.receiveNoWait();
        assertNull(message);
        LOGGER.info("No messages redelivered as is expected");
    }

    public void testRecoverResendsMsgsAckOnEarlier() throws Exception
    {
        initTest();

        Message message = validateNextMessages(2, 0);
        message.acknowledge();
        LOGGER.info("Received 2 messages, acknowledge() first message, should acknowledge both");

        _consumer.receive();
        _consumer.receive();
        LOGGER.info("Received all four messages. Calling recover with two outstanding messages");
        // no ack for last three messages so when I call recover I expect to get three messages back
        _consumerSession.recover();

        Message message2 = _consumer.receive(_timeout);
        assertNotNull(message2);
        assertEquals(2, message2.getIntProperty(INDEX));

        Message message3 = _consumer.receive(_timeout);
        assertNotNull(message3);
        assertEquals(3, message3.getIntProperty(INDEX));

        LOGGER.info("Received redelivery of two messages. calling acknolwedgeThis() first of those message");
        ((org.apache.qpid.jms.Message) message2).acknowledgeThis();

        LOGGER.info("Calling recover");
        // all acked so no messages to be delivered
        _consumerSession.recover();

        message3 = _consumer.receive(_timeout);
        assertNotNull(message3);
        assertEquals(3, message3.getIntProperty(INDEX));
        ((org.apache.qpid.jms.Message) message3).acknowledgeThis();

        // all acked so no messages to be delivered
        validateRemainingMessages(0);
    }

    /**
     * Goal : Same as testOderingWithSyncConsumer
     * Test strategy :
     * Same as testOderingWithSyncConsumer but using a 
     * Message Listener instead of a sync receive().
     */
    public void testOrderingWithAsyncConsumer() throws Exception
    {
        Connection con = getConnection();
        final Session session = con.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Destination topic = createTopic(con, "myTopic");
        MessageConsumer cons = session.createConsumer(topic);
        
        sendMessage(session,topic,8);
        con.start();

        final Object lock = new Object();
        final AtomicBoolean pass = new AtomicBoolean(false); //used as work around for 'final'

        cons.setMessageListener(new MessageListener()
        {               
            private int messageSeen = 0;
            private int expectedIndex = 0;

            @Override
            public void onMessage(Message message)
            {
                try
                {
                    int actualIndex = message.getIntProperty(INDEX);
                    assertEquals("Received Message Out Of Order", expectedIndex, actualIndex);
                                
                    //don't ack the message until we receive it 5 times
                    if( messageSeen < 5 ) 
                    {
                        LOGGER.debug("Ignoring message " + actualIndex + " and calling recover");
                        session.recover();
                        messageSeen++;
                    }
                    else
                    {
                        messageSeen = 0;
                        expectedIndex++;
                        message.acknowledge();
                        LOGGER.debug("Acknowledging message " + actualIndex);
                        if (expectedIndex == 8)
                        {
                            pass.set(true);
                            synchronized (lock) 
                            {
                                lock.notifyAll();
                            }      
                        }
                    }                    
                } 
                catch (JMSException e)
                {
                    _error = e;
                    synchronized (lock) 
                    {
                        lock.notifyAll();
                    }  
                }
            }
        });
        
        synchronized(lock)
        {
            // Based on historical data, on average the test takes about 6 secs to complete.
            lock.wait(8000);
        }

        assertNull("Unexpected exception thrown by async listener", _error);

        if (!pass.get())
        {
            fail("Test did not complete on time. Please check the logs");
        }
    }

    /**
     * This test ensures that after exhausting credit (prefetch), a {@link Session#recover()} successfully
     * restores credit and allows the same messages to be re-received.
     */
    public void testRecoverSessionAfterCreditExhausted() throws Exception
    {
        final int maxPrefetch = 5;

        // We send more messages than prefetch size.  This ensure that if the 0-10 client were to
        // complete the message commands before the rollback command is sent, the broker would
        // send additional messages utilising the release credit.  This problem would manifest itself
        // as an incorrect message (or no message at all) being received at the end of the test.

        final int numMessages = maxPrefetch * 2;

        setTestClientSystemProperty(ClientProperties.MAX_PREFETCH_PROP_NAME, String.valueOf(maxPrefetch));

        Connection con = getConnection();
        final javax.jms.Session session = con.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Destination dest = session.createQueue(getTestQueueName());
        MessageConsumer cons = session.createConsumer(dest);

        sendMessage(session, dest, numMessages);
        con.start();

        for (int i=0; i< maxPrefetch; i++)
        {
            final Message message = cons.receive(_timeout);
            assertNotNull("Received:" + i, message);
            assertEquals("Unexpected message received", i, message.getIntProperty(INDEX));
        }

        LOGGER.info("Recovering");
        session.recover();

        Message result = cons.receive(_timeout);
        // Expect the first message
        assertEquals("Unexpected message received", 0, result.getIntProperty(INDEX));
    }

}

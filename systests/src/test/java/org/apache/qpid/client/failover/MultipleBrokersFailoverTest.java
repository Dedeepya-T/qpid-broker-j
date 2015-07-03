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
package org.apache.qpid.client.failover;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.client.AMQConnection;
import org.apache.qpid.client.AMQConnectionURL;
import org.apache.qpid.jms.ConnectionListener;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.util.FileUtils;

public class MultipleBrokersFailoverTest extends QpidBrokerTestCase implements ConnectionListener
{
    private static final Logger _logger = LoggerFactory.getLogger(MultipleBrokersFailoverTest.class);

    private static final String FAILOVER_VIRTUAL_HOST = "failover";
    private static final String NON_FAILOVER_VIRTUAL_HOST = "nonfailover";
    private static final String BROKER_PORTION_FORMAT = "tcp://localhost:%d?connectdelay='%d',retries='%d'";
    private static final int FAILOVER_RETRIES = 0;
    private static final int FAILOVER_CONNECTDELAY = 0;
    private static final int FAILOVER_AWAIT_TIME = 10000;


    private int[] _brokerPorts;
    private AMQConnectionURL _connectionURL;
    private Connection _connection;
    private CountDownLatch _failoverComplete;
    private CountDownLatch _failoverStarted;
    private Session _consumerSession;
    private Destination _destination;
    private MessageConsumer _consumer;
    private Session _producerSession;
    private MessageProducer _producer;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        int numBrokers = 4;
        int port = getPort();
        _brokerPorts = new int[numBrokers];

        // we need to create 4 brokers:
        // 1st broker will be running in test JVM and will not have failover host (only tcp connection will established, amqp connection will be closed)
        // 2d broker will be spawn in separate JVM and should have a failover host (amqp connection should be established)
        // 3d broker will be spawn in separate JVM and should not have a failover host (only tcp connection will established, amqp connection will be closed)
        // 4d broker will be spawn in separate JVM and should have a failover host (amqp connection should be established)

        // the test should connect to the second broker first and fail over to the forth broker
        // after unsuccessful try to establish the connection to the 3d broker
        for (int i = 0; i < numBrokers; i++)
        {
            if (i > 0)
            {
                port = getNextAvailable(port + 1);
            }
            _brokerPorts[i] = port;

            createBrokerConfiguration(port);
            String host = null;
            if (i == 1 || i == _brokerPorts.length - 1)
            {
                host = FAILOVER_VIRTUAL_HOST;
            }
            else
            {
                host = NON_FAILOVER_VIRTUAL_HOST;
            }
            createTestVirtualHostNode(port, host);

            startBroker(port);
            revertSystemProperties();
        }

        _connectionURL = new AMQConnectionURL(generateUrlString(numBrokers));

        _connection = getConnection(_connectionURL);
        ((AMQConnection) _connection).setConnectionListener(this);
        _failoverComplete = new CountDownLatch(1);
        _failoverStarted = new CountDownLatch(1);
    }

    public void startBroker() throws Exception
    {
        // noop, prevent the broker startup in super.setUp()
    }

    private String generateUrlString(int numBrokers)
    {
        String baseString = "amqp://guest:guest@test/" + FAILOVER_VIRTUAL_HOST
                            + "?&failover='roundrobin?cyclecount='1''&brokerlist='";
        StringBuffer buffer = new StringBuffer(baseString);

        for(int i = 0; i< numBrokers ; i++)
        {
            if(i != 0)
            {
                buffer.append(";");
            }

            String broker = String.format(BROKER_PORTION_FORMAT, _brokerPorts[i],
                                          FAILOVER_CONNECTDELAY, FAILOVER_RETRIES);
            buffer.append(broker);
        }
        buffer.append("'");

        return buffer.toString();
    }

    public void tearDown() throws Exception
    {
        try
        {
            super.tearDown();
        }
        finally
        {
            for (int i = 0; i < _brokerPorts.length; i++)
            {
                if (_brokerPorts[i] > 0)
                {
                    stopBrokerSafely(_brokerPorts[i]);
                    FileUtils.deleteDirectory(System.getProperty("QPID_WORK") + File.separator + getFailingPort());
                }
            }

        }
    }


    public void testFailoverOnBrokerKill() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);
        assertConnectionPort(_brokerPorts[1]);

        assertSendReceive(0);

        killBroker(_brokerPorts[1]);

        awaitForFailoverCompletion(FAILOVER_AWAIT_TIME);
        assertEquals("Failover is not started as expected", 0, _failoverStarted.getCount());

        assertSendReceive(2);
        assertConnectionPort(_brokerPorts[_brokerPorts.length - 1]);
    }

    public void testFailoverOnBrokerStop() throws Exception
    {
        init(Session.SESSION_TRANSACTED, true);
        assertConnectionPort(_brokerPorts[1]);

        assertSendReceive(0);

        stopBroker(_brokerPorts[1]);

        awaitForFailoverCompletion(FAILOVER_AWAIT_TIME);
        assertEquals("Failover is not started as expected", 0, _failoverStarted.getCount());

        assertSendReceive(1);
        assertConnectionPort(_brokerPorts[_brokerPorts.length - 1]);
    }

    private void assertConnectionPort(int brokerPort)
    {
        int connectionPort = ((AMQConnection)_connection).getActiveBrokerDetails().getPort();
        assertEquals("Unexpected broker port", brokerPort, connectionPort);
    }

    private void assertSendReceive(int index) throws JMSException
    {
        Message message = createNextMessage(_producerSession, index);
        _producer.send(message);
        if (_producerSession.getTransacted())
        {
            _producerSession.commit();
        }
        Message receivedMessage = _consumer.receive(1000l);
        assertReceivedMessage(receivedMessage, index);
        if (_consumerSession.getTransacted())
        {
            _consumerSession.commit();
        }
    }

    private void awaitForFailoverCompletion(long delay) throws Exception
    {
        _logger.info("Awaiting Failover completion..");
        if (!_failoverComplete.await(delay, TimeUnit.MILLISECONDS))
        {
            fail("Failover did not complete within " + delay + "ms.");
        }
    }

    private void assertReceivedMessage(Message receivedMessage, int messageIndex)
    {
        assertNotNull("Expected message [" + messageIndex + "] is not received!", receivedMessage);
        assertTrue(
                "Failure to receive message [" + messageIndex + "], expected TextMessage but received " + receivedMessage,
                receivedMessage instanceof TextMessage);
    }

    private void init(int acknowledgeMode, boolean startConnection) throws Exception
    {
        boolean isTransacted = acknowledgeMode == Session.SESSION_TRANSACTED ? true : false;

        _consumerSession = _connection.createSession(isTransacted, acknowledgeMode);
        _destination = _consumerSession.createQueue(getTestQueueName());
        _consumer = _consumerSession.createConsumer(_destination);

        if (startConnection)
        {
            _connection.start();
        }

        _producerSession = _connection.createSession(isTransacted, acknowledgeMode);
        _producer = _producerSession.createProducer(_destination);

    }

    @Override
    public void bytesSent(long count)
    {
    }

    @Override
    public void bytesReceived(long count)
    {
    }

    @Override
    public boolean preFailover(boolean redirect)
    {
        _failoverStarted.countDown();
        return true;
    }

    @Override
    public boolean preResubscribe()
    {
        return true;
    }

    @Override
    public void failoverComplete()
    {
        _failoverComplete.countDown();
    }
}

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
package org.apache.qpid.systest.management.amqp;

import static org.apache.qpid.server.model.Queue.ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES;
import static org.apache.qpid.test.utils.TestSSLConstants.JAVA_KEYSTORE_TYPE;
import static org.apache.qpid.test.utils.TestSSLConstants.TRUSTSTORE;
import static org.apache.qpid.test.utils.TestSSLConstants.TRUSTSTORE_PASSWORD;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ConnectionMetaData;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.qpid.server.exchange.ExchangeDefaults;
import org.apache.qpid.server.model.DefaultVirtualHostAlias;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.VirtualHostAlias;
import org.apache.qpid.server.model.VirtualHostNameAlias;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.queue.PriorityQueue;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class AmqpManagementTest extends QpidBrokerTestCase
{
    private Connection _connection;
    private Session _session;
    private Queue _queue;
    private Queue _replyAddress;
    private Queue _replyConsumer;
    private MessageConsumer _consumer;
    private MessageProducer _producer;
    private boolean _runTest = true;

    @Override
    public void setUp() throws Exception
    {
        TestBrokerConfiguration config = getDefaultBrokerConfiguration();

        Map<String, Object> ampqSslPort = new HashMap<>();
        ampqSslPort.put(Port.TRANSPORTS, Collections.singletonList(Transport.SSL));
        ampqSslPort.put(Port.PORT, DEFAULT_SSL_PORT);
        ampqSslPort.put(Port.NAME, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT);
        ampqSslPort.put(Port.KEY_STORE, TestBrokerConfiguration.ENTRY_NAME_SSL_KEYSTORE);
        ampqSslPort.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER);
        ampqSslPort.put(Port.PROTOCOLS, System.getProperty(TEST_AMQP_PORT_PROTOCOLS_PROPERTY));

        config.addObjectConfiguration(Port.class, ampqSslPort);

        Map<String, Object> aliasAttributes = new HashMap<>();
        aliasAttributes.put(VirtualHostAlias.NAME, "defaultAlias");
        aliasAttributes.put(VirtualHostAlias.TYPE, DefaultVirtualHostAlias.TYPE_NAME);
        getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT, VirtualHostAlias.class, aliasAttributes);

        aliasAttributes = new HashMap<>();
        aliasAttributes.put(VirtualHostAlias.NAME, "nameAlias");
        aliasAttributes.put(VirtualHostAlias.TYPE, VirtualHostNameAlias.TYPE_NAME);
        getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT, VirtualHostAlias.class, aliasAttributes);

        // set the ssl system properties
        setSystemProperty("javax.net.ssl.trustStore", TRUSTSTORE);
        setSystemProperty("javax.net.ssl.trustStorePassword", TRUSTSTORE_PASSWORD);
        setSystemProperty("javax.net.ssl.trustStoreType", JAVA_KEYSTORE_TYPE);
        setSystemProperty("javax.net.ssl.keyStoreType", JAVA_KEYSTORE_TYPE);

        super.setUp();

        if (isBroker10())
        {
            _runTest = true;
        }
        else
        {
            Connection con = getConnection();
            final ConnectionMetaData metaData = con.getMetaData();
            // TODO: Older Qpid JMS Client 0-x (<=6.1.x) didn't support management addresses.
            _runTest =  !( metaData.getProviderMajorVersion() < 6 || (metaData.getProviderMajorVersion() == 6 && metaData.getProviderMinorVersion() <= 1));
            con.close();
        }
        setSystemProperty("test.port.ssl", ""+getDefaultBroker().getAmqpTlsPort());
    }

    private void setupSession() throws Exception
    {
        _connection.start();
        _session = _connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        if(isBroker10())
        {
            _queue = _session.createQueue("$management");
            _replyAddress = _session.createTemporaryQueue();
            _replyConsumer = _replyAddress;
        }
        else
        {
            _queue = _session.createQueue("ADDR:$management");
            _replyAddress = _session.createQueue("ADDR:!response");
            _replyConsumer = _session.createQueue(
                    "ADDR:$management ; {assert : never, node: { type: queue }, link:{name: \"!response\"}}");
        }
        _consumer = _session.createConsumer(_replyConsumer);
        _producer = _session.createProducer(_queue);
    }

    private void setupBrokerManagementConnection() throws Exception
    {
        ConnectionFactory management =
                isBroker10() ? getConnectionFactory("default", "$management", UUID.randomUUID().toString())
                        : getConnectionFactory("management");

        _connection = management.createConnection(GUEST_USERNAME, GUEST_PASSWORD);
        setupSession();
    }

    private void setupVirtualHostManagementConnection() throws Exception
    {
        _connection = getConnection();
        setupSession();
    }

    // test get types on $management
    public void testGetTypesOnBrokerManagement() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();

        Message message = _session.createBytesMessage();

        message.setStringProperty("identity", "self");
        message.setStringProperty("type", "org.amqp.management");
        message.setStringProperty("operation", "GET-TYPES");

        message.setJMSReplyTo(_replyAddress);

        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertNotNull("The response did not include the org.amqp.Management type", getValueFromMapResponse(responseMessage, "org.amqp.management"));
        assertNotNull("The response did not include the org.apache.qpid.Port type", getValueFromMapResponse(responseMessage, "org.apache.qpid.Port"));

    }

    private void checkResponseIsMapType(final Message responseMessage) throws JMSException
    {
        if (isBroker10())
        {
            if (!(responseMessage instanceof MapMessage)
                && !(responseMessage instanceof ObjectMessage
                      && ((ObjectMessage) responseMessage).getObject() instanceof Map))
            {
                fail(String.format("The response was neither a Map Message nor an Object Message containing a Map. It was a : %s ",
                                   responseMessage.getClass()));
            }
        }
        else
        {
            assertTrue(String.format("The response was not a MapMessage. It was a '%s'.", responseMessage.getClass()), responseMessage instanceof MapMessage);
        }
    }

    private Object getValueFromMapResponse(final Message responseMessage, String name) throws JMSException
    {
        if (isBroker10() && responseMessage instanceof ObjectMessage)
        {
            return ((Map)((ObjectMessage)responseMessage).getObject()).get(name);
        }
        else
        {
            return ((MapMessage) responseMessage).getObject(name);
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<String> getMapResponseKeys(final Message responseMessage) throws JMSException
    {
        if (isBroker10() && responseMessage instanceof ObjectMessage)
        {
            return ((Map)((ObjectMessage)responseMessage).getObject()).keySet();
        }
        else
        {
            return Collections.list(((MapMessage) responseMessage).getMapNames());
        }
    }

    // test get types on $management
    public void testQueryBrokerManagement() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();


        MapMessage message = _session.createMapMessage();

        message.setStringProperty("identity", "self");
        message.setStringProperty("type", "org.amqp.management");
        message.setStringProperty("operation", "QUERY");
        message.setObject("attributeNames", "[]");
        message.setJMSReplyTo(_replyAddress);

        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        assertEquals("The correlation id does not match the sent message's messageId", message.getJMSMessageID(), responseMessage.getJMSCorrelationID());
        checkResponseIsMapType(responseMessage);
        List<String> resultMessageKeys = new ArrayList<>(getMapResponseKeys(responseMessage));
        assertEquals("The response map has two entries", 2, resultMessageKeys.size());
        assertTrue("The response map does not contain attribute names", resultMessageKeys.contains("attributeNames"));
        assertTrue("The response map does not contain results ", resultMessageKeys.contains("results"));
        Object attributeNames = getValueFromMapResponse(responseMessage, "attributeNames");
        assertTrue("The attribute names are not a list", attributeNames instanceof Collection);
        Collection attributeNamesCollection = (Collection)attributeNames;
        assertTrue("The attribute names do not contain identity", attributeNamesCollection.contains("identity"));
        assertTrue("The attribute names do not contain name", attributeNamesCollection.contains("name"));

        assertTrue("The attribute names do not contain qpid-type", attributeNamesCollection.contains("qpid-type"));

        // Now test filtering by type
        message.setStringProperty("identity", "self");
        message.setStringProperty("type", "org.amqp.management");
        message.setStringProperty("operation", "QUERY");
        message.setStringProperty("entityType", "org.apache.qpid.Exchange");

        message.setObject("attributeNames", "[\"name\", \"identity\", \"type\"]");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);

        assertEquals("The correlation id does not match the sent message's messageId", message.getJMSMessageID(), responseMessage.getJMSCorrelationID());
        resultMessageKeys = new ArrayList<>(getMapResponseKeys(responseMessage));
        assertEquals("The response map has two entries", 2, resultMessageKeys.size());
        assertTrue("The response map does not contain attribute names", resultMessageKeys.contains("attributeNames"));
        assertTrue("The response map does not contain results ", resultMessageKeys.contains("results"));
        attributeNames = getValueFromMapResponse(responseMessage, "attributeNames");
        assertTrue("The attribute names are not a list", attributeNames instanceof Collection);
        attributeNamesCollection = (Collection)attributeNames;
        assertEquals("The attributeNames are no as expected", Arrays.asList("name", "identity", "type"), attributeNamesCollection);
        Object resultsObject = getValueFromMapResponse(responseMessage, "results");
        assertTrue("results is not a collection", resultsObject instanceof Collection);
        Collection results = (Collection)resultsObject;

        final int numberOfExchanges = results.size();
        assertTrue("results should have at least 4 elements", numberOfExchanges >= 4);

        message.setStringProperty("identity", "self");
        message.setStringProperty("type", "org.amqp.management");
        message.setStringProperty("operation", "QUERY");
        message.setStringProperty("entityType", "org.apache.qpid.DirectExchange");

        message.setObject("attributeNames", "[\"name\", \"identity\", \"type\"]");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        final Collection directExchanges = (Collection) getValueFromMapResponse(responseMessage, "results");
        assertTrue("There are the same number of results when searching for direct exchanges as when searching for all exchanges", directExchanges.size() < numberOfExchanges);
        assertTrue("The list of direct exchanges is not a proper subset of the list of all exchanges", results.containsAll(directExchanges));
    }


    // test get types on a virtual host
    public void testGetTypesOnVhostManagement() throws Exception
    {

        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        Message message = _session.createBytesMessage();

        message.setStringProperty("identity", "self");
        message.setStringProperty("type", "org.amqp.management");
        message.setStringProperty("operation", "GET-TYPES");
        byte[] correlationID = "some correlation id".getBytes();
        message.setJMSCorrelationIDAsBytes(correlationID);

        message.setJMSReplyTo(_replyAddress);

        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertNotNull("A response message was not sent", responseMessage);
        assertTrue("The correlation id does not match the sent message's correlationId", Arrays.equals(correlationID, responseMessage.getJMSCorrelationIDAsBytes()));

        assertResponseCode(responseMessage, 200);

        assertTrue("The response was not a MapMessage", responseMessage instanceof MapMessage);
        assertNotNull("The response did not include the org.amqp.Management type",
                      ((MapMessage) responseMessage).getObject("org.amqp.management"));
        assertNull("The response included the org.apache.qpid.Port type",
                   ((MapMessage) responseMessage).getObject("org.apache.qpid.Port"));



    }

    // create / update / read / delete a queue via $management
    public void testCreateQueueOnBrokerManagement() throws Exception
    {

        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        message.setLong(ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES, 100L);
        String path = "test/test/" + getTestName();
        message.setString("object-path", path);
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 201);
        checkResponseIsMapType(responseMessage);
        assertEquals("The created queue was not a standard queue", "org.apache.qpid.StandardQueue", getValueFromMapResponse(responseMessage, "type"));
        assertEquals("The created queue was not a standard queue", "standard", getValueFromMapResponse(responseMessage, "qpid-type"));
        assertEquals("the created queue did not have the correct alerting threshold", 100L, getValueFromMapResponse(responseMessage,ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES));
        Object identity = getValueFromMapResponse(responseMessage,"identity");

        message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "UPDATE");
        message.setObjectProperty("identity", identity);
        message.setLong(ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES, 250L);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertEquals("the created queue did not have the correct alerting threshold", 250L, getValueFromMapResponse(responseMessage, ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES));

        message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "DELETE");
        message.setObjectProperty("index", "object-path");
        message.setObjectProperty("key", path);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 204);

        message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "READ");
        message.setObjectProperty("identity", identity);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 404);
    }

    // create / update / read / delete a queue via vhost

    public void testCreateQueueOnVhostManagement() throws Exception
    {

        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        message.setInt(PriorityQueue.PRIORITIES, 13);
        String path = getTestName();
        message.setString("object-path", path);
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 201);
        checkResponseIsMapType(responseMessage);
        assertEquals("The created queue was not a priority queue", "org.apache.qpid.PriorityQueue", getValueFromMapResponse(responseMessage, "type"));
        assertEquals("The created queue was not a standard queue", "priority", getValueFromMapResponse(responseMessage, "qpid-type"));
        assertEquals("the created queue did not have the correct number of priorities", 13, Integer.valueOf(getValueFromMapResponse(responseMessage, PriorityQueue.PRIORITIES).toString()).intValue());
        Object identity = getValueFromMapResponse(responseMessage, "identity");

        // Trying to create a second queue with the same name should cause a conflict
        message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        message.setInt(PriorityQueue.PRIORITIES, 7);
        message.setString("object-path", getTestName());
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 409);

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "READ");
        message.setObjectProperty("identity", identity);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        assertEquals("the queue did not have the correct number of priorities", 13, Integer.valueOf(getValueFromMapResponse(responseMessage, PriorityQueue.PRIORITIES).toString()).intValue());
        assertEquals("the queue did not have the expected path", getTestName(), getValueFromMapResponse(responseMessage, "object-path"));


        message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "UPDATE");
        message.setObjectProperty("identity", identity);
        message.setLong(ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES, 250L);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertEquals("The updated queue did not have the correct alerting threshold", 250L, Long.valueOf(getValueFromMapResponse(responseMessage, ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES).toString()).longValue());


        message = _session.createMapMessage();
        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "DELETE");
        message.setObjectProperty("index", "object-path");
        message.setObjectProperty("key", path);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 204);

        message = _session.createMapMessage();
        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "DELETE");
        message.setObjectProperty("index", "object-path");
        message.setObjectProperty("key", path);

        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 404);
    }

    // read virtual host from virtual host management
    public void testReadVirtualHost() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.VirtualHost");
        message.setStringProperty("operation", "READ");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertEquals("The name of the virtual host is not as expected", "test", getValueFromMapResponse(responseMessage, "name"));

        message.setBooleanProperty("actuals", false);
        _producer.send(message);
        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertNotNull("Derived attribute (productVersion) should be available", getValueFromMapResponse(responseMessage, "productVersion"));
    }

    public void testReadObject_ObjectNotFound() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Exchange");
        message.setStringProperty("operation", "READ");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "not-found-exchange");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 404);
    }

    public void testInvokeOperation_ObjectNotFound() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Exchange");
        message.setStringProperty("operation", "getStatistics");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "not-found-exchange");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 404);
    }

    public void testInvokeOperationReturningMap() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Broker");
        message.setStringProperty("operation", "getStatistics");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertNotNull(getValueFromMapResponse(responseMessage, "numberOfLiveThreads"));
    }

    public void testInvokeOperationReturningManagedAttributeValue() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Broker");
        message.setStringProperty("operation", "getConnectionMetaData");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertNotNull(getValueFromMapResponse(responseMessage, "port"));
    }

    public void testInvokeSecureOperation() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        String secureOperation = "publishMessage";  // // a secure operation
        Map<String, String> operationArg = new HashMap<>();
        operationArg.put("address", ExchangeDefaults.FANOUT_EXCHANGE_NAME);
        operationArg.put("content", "Hello, world!");

        setupVirtualHostManagementConnection();

        MapMessage plainRequest = _session.createMapMessage();

        plainRequest.setStringProperty("type", "org.apache.qpid.VirtualHost");
        plainRequest.setStringProperty("operation", secureOperation);
        plainRequest.setStringProperty("index", "object-path");
        plainRequest.setStringProperty("key", "");
        plainRequest.setStringProperty("message", new ObjectMapper().writeValueAsString(operationArg));
        plainRequest.setJMSReplyTo(_replyAddress);
        _producer.send(plainRequest);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 403);

        _connection.close();

        _connection = getConnectionBuilder().setTls(true).build();
        setupSession();
        MapMessage secureRequest = _session.createMapMessage();

        secureRequest.setStringProperty("type", "org.apache.qpid.VirtualHost");
        secureRequest.setStringProperty("operation", secureOperation);
        secureRequest.setStringProperty("index", "object-path");
        secureRequest.setStringProperty("key", "");
        secureRequest.setStringProperty("message", new ObjectMapper().writeValueAsString(operationArg));
        secureRequest.setJMSReplyTo(_replyAddress);
        _producer.send(secureRequest);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
   //     responseMessage.get
    }

    // create a virtual host from $management
    public void testCreateVirtualHost() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();
        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.JsonVirtualHostNode");
        message.setStringProperty("operation", "CREATE");
        String virtualHostName = "newMemoryVirtualHost";
        message.setString("name", virtualHostName);
        message.setString(VirtualHostNode.VIRTUALHOST_INITIAL_CONFIGURATION, "{ \"type\" : \"Memory\" }");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 201);
        _connection.close();
        _connection = getConnectionForVHost(virtualHostName);
        setupSession();

        message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.VirtualHost");
        message.setStringProperty("operation", "READ");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 200);
        checkResponseIsMapType(responseMessage);
        assertEquals("The name of the virtual host is not as expected", virtualHostName, getValueFromMapResponse(responseMessage, "name"));
        assertEquals("The type of the virtual host is not as expected", "Memory", getValueFromMapResponse(responseMessage, "qpid-type"));


    }
    // attempt to delete the virtual host via the virtual host
    public void testDeleteVirtualHost() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();
        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.VirtualHost");
        message.setStringProperty("operation", "DELETE");
        message.setStringProperty("index", "object-path");
        message.setStringProperty("key", "");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 501);
    }

    // create a queue with the qpid type
    public void testCreateQueueWithQpidType() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Queue");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        message.setString("qpid-type", "lvq");
        String path = getTestName();
        message.setString("object-path", path);
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 201);
        checkResponseIsMapType(responseMessage);
        assertEquals("The created queue did not have the correct type", "org.apache.qpid.LastValueQueue", getValueFromMapResponse(responseMessage, "type"));
    }

    // create a queue using the AMQP type
    public void testCreateQueueWithAmqpType() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.SortedQueue");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        String path = getTestName();
        message.setString("object-path", path);
        message.setString("sortKey", "foo");
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 201);
        checkResponseIsMapType(responseMessage);
        assertEquals("The created queue did not have the correct type", "sorted", getValueFromMapResponse(responseMessage, "qpid-type"));
    }

    // attempt to create an exchange without a type
    public void testCreateExchangeWithoutType() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Exchange");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        String path = getTestName();
        message.setString("object-path", path);
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 400);
    }



    // attempt to create a connection
    public void testCreateConnectionOnVhostManagement() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupVirtualHostManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Connection");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        String path = getTestName();
        message.setString("object-path", path);
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 501);
    }

    public void testCreateConnectionOnBrokerManagement() throws Exception
    {
        if (!_runTest)
        {
            return;
        }
        setupBrokerManagementConnection();

        MapMessage message = _session.createMapMessage();

        message.setStringProperty("type", "org.apache.qpid.Connection");
        message.setStringProperty("operation", "CREATE");
        message.setString("name", getTestName());
        String path = getTestName();
        message.setString("object-path", path);
        message.setJMSReplyTo(_replyAddress);
        _producer.send(message);

        Message responseMessage = _consumer.receive(getReceiveTimeout());
        assertResponseCode(responseMessage, 501);

    }

    @SuppressWarnings("unchecked")
    private void assertResponseCode(final Message responseMessage, final int expectedResponseCode) throws JMSException
    {
        assertNotNull("A response message was not sent", responseMessage);
        assertTrue("The response message does not have a status code",
                   Collections.list(responseMessage.getPropertyNames()).contains("statusCode"));
        assertEquals("The response code did not indicate success",
                     expectedResponseCode, responseMessage.getIntProperty("statusCode"));
    }


}

/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.origin.jms;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.sdk.SourceRunner;
import com.streamsets.pipeline.sdk.StageRunner;
import com.streamsets.pipeline.stage.origin.lib.BasicConfig;
import com.streamsets.pipeline.stage.origin.lib.CredentialsConfig;
import com.streamsets.pipeline.stage.origin.lib.DataFormatConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.security.AuthenticationUser;
import org.apache.activemq.security.SimpleAuthenticationPlugin;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TestJmsSource {
  private static final Logger LOG = LoggerFactory.getLogger(TestJmsSource.class);

  private final static String INITIAL_CONTEXT_FACTORY = "org.apache.activemq.jndi.ActiveMQInitialContextFactory";
  private final static String CONNECTION_FACTORY = "ConnectionFactory";
  private final static String BROKER_BIND_URL = "tcp://localhost:61516";
  private final static  String DESTINATION_NAME = "test";
  private final static  String USERNAME = "user";
  private final static String PASSWORD = "pass";
  // specific for dynamic queues on ActiveMq
  public static final String JNDI_PREFIX = "dynamicQueues/";
  private File baseDir;
  private File tmpDir;
  private File dataDir;
  private File passwordFile;

  private BrokerService broker;
  private BasicConfig basicConfig;
  private CredentialsConfig credentialsConfig;
  private DataFormatConfig dataFormatConfig;
  private JmsConfig jmsConfig;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    baseDir = Files.createTempDir();
    tmpDir = new File(baseDir, "tmp");
    dataDir = new File(baseDir, "data");
    Assert.assertTrue(tmpDir.mkdir());
    passwordFile = new File(baseDir, "password");
    Files.write(PASSWORD.getBytes(StandardCharsets.UTF_8), passwordFile);

    broker = new BrokerService();

    broker.addConnector(BROKER_BIND_URL);
    broker.setTmpDataDirectory(tmpDir);
    broker.setDataDirectoryFile(dataDir);
    List<AuthenticationUser> users = Lists.newArrayList();
    users.add(new AuthenticationUser(USERNAME, PASSWORD, ""));
    SimpleAuthenticationPlugin authentication = new SimpleAuthenticationPlugin(users);
    broker.setPlugins(new BrokerPlugin[]{authentication});
    broker.start();

    basicConfig = new BasicConfig();
    credentialsConfig = new CredentialsConfig();
    dataFormatConfig = new DataFormatConfig();
    jmsConfig = new JmsConfig();
    credentialsConfig.useCredentials = true;
    credentialsConfig.username = USERNAME;
    credentialsConfig.password = PASSWORD;
    dataFormatConfig.dataFormat = DataFormat.TEXT;
    dataFormatConfig.removeCtrlChars = true;
    jmsConfig.initialContextFactory = INITIAL_CONTEXT_FACTORY;
    jmsConfig.connectionFactory = CONNECTION_FACTORY;
    jmsConfig.destinationName = JNDI_PREFIX + DESTINATION_NAME;
    jmsConfig.providerURL = BROKER_BIND_URL;
  }

  @After
  public void tearDown() throws Exception {
    if (broker != null) {
      broker.stop();
    }
  }

  private void putQueue(List<String> events) throws Exception {
    ConnectionFactory factory = new ActiveMQConnectionFactory(USERNAME,
      PASSWORD, BROKER_BIND_URL);
    Connection connection = factory.createConnection();
    connection.start();

    Session session = connection.createSession(true,
      Session.AUTO_ACKNOWLEDGE);
    Destination destination = session.createQueue(DESTINATION_NAME);
    MessageProducer producer = session.createProducer(destination);

    int i = 0;
    for(String event : events) {
      int remainder = i++ % 3;
      if (remainder == 0) {
        TextMessage message = session.createTextMessage();
        message.setText(event);
        producer.send(message);
      } else if (remainder == 1) {
        BytesMessage message = session.createBytesMessage();
        message.writeBytes(event.getBytes(StandardCharsets.UTF_8));
        producer.send(message);
      } else {
        BytesMessage message = session.createBytesMessage();
        message.writeUTF(event); //causes control characters to be included
        producer.send(message);
      }
    }
    session.commit();
    session.close();
    connection.close();
  }

  private SourceRunner createRunner() {
    JmsSource origin = new JmsSource(basicConfig, credentialsConfig, dataFormatConfig, jmsConfig,
      new JmsMessageConsumerFactoryImpl(), new JmsMessageConverterImpl(dataFormatConfig), new InitialContextFactory());
    SourceRunner runner = new SourceRunner.Builder(JmsSource.class, origin)
      .addOutputLane("lane")
      .build();
    return runner;
  }

  private void runInit(String expectedError) {
    SourceRunner runner = createRunner();
    try {
      runner.runInit();
      Assert.fail();
    } catch (StageException ex) {
      Assert.assertTrue(Utils.format("Expected {} got: {}", expectedError, ex), ex.getMessage().
        contains(expectedError + " "));
    }
  }

  @Test
  public void testInvalidInitialContext() throws Exception {
    jmsConfig.initialContextFactory = "invalid";
    runInit("JMS_00");
  }

  @Test
  public void testInvalidConnectionFactory() throws Exception {
    jmsConfig.connectionFactory = "invalid";
    runInit("JMS_01");
  }

  @Test
  public void testInvalidDestination() throws Exception {
    jmsConfig.destinationName = "invalid";
    runInit("JMS_05");
  }

  @Test
  public void testInvalidCreds() throws Exception {
    credentialsConfig.username = "invalid";
    runInit("JMS_04");
  }

  @Test
  public void testNoBroker() throws Exception {
    broker.stop();
    runInit("JMS_03");
  }


  @Test
  public void testSuccess() throws Exception {
    int numRecords = 20;
    List<String> expected = Lists.newArrayList();
    for (int i = 0; i < numRecords; i++) {
      expected.add(String.valueOf(i));
    }
    putQueue(expected);
    SourceRunner runner = createRunner();
    runner.runInit();
    try {
      // Check that existing rows are loaded.
      StageRunner.Output output = runner.runProduce(null, numRecords * 2);
      Map<String, List<Record>> recordMap = output.getRecords();
      List<Record> parsedRecords = recordMap.get("lane");
      Assert.assertEquals(numRecords, parsedRecords.size());
      List<String> actual = Lists.newArrayList();
      for (Record record : parsedRecords) {
        actual.add(record.get("/text").getValueAsString());
      }
      Assert.assertEquals(expected, actual);
    } finally {
      runner.runDestroy();
    }
  }
}
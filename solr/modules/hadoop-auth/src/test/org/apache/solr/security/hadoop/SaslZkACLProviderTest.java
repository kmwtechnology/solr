/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.security.hadoop;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.tests.util.QuickPatchThreadsFilter;
import org.apache.lucene.util.Constants;
import org.apache.solr.SolrIgnoredThreadsFilter;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.cloud.AbstractDigestZkACLAndCredentialsProvidersTestBase;
import org.apache.solr.cloud.AbstractZkTestCase;
import org.apache.solr.cloud.ZkTestServer;
import org.apache.solr.common.cloud.DefaultZkACLProvider;
import org.apache.solr.common.cloud.SaslZkACLProvider;
import org.apache.solr.common.cloud.SecurityAwareZkACLProvider;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkACLProvider;
import org.apache.solr.util.BadZookeeperThreadsFilter;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadLeakFilters(
    defaultFilters = true,
    filters = {
      SolrIgnoredThreadsFilter.class,
      QuickPatchThreadsFilter.class,
      BadZookeeperThreadsFilter.class
    })
public class SaslZkACLProviderTest extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Charset DATA_ENCODING = StandardCharsets.UTF_8;

  protected ZkTestServer zkServer;

  @BeforeClass
  public static void beforeClass() {
    assumeFalse(
        "FIXME: SOLR-7040: This test fails under IBM J9", Constants.JAVA_VENDOR.startsWith("IBM"));
    System.setProperty("solrcloud.skip.autorecovery", "true");
    System.setProperty("hostName", "localhost");
  }

  @AfterClass
  public static void afterClass() {
    System.clearProperty("solrcloud.skip.autorecovery");
    System.clearProperty("hostName");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    if (log.isInfoEnabled()) {
      log.info("####SETUP_START {}", getTestName());
    }
    createTempDir();

    Path zkDir = createTempDir().resolve("zookeeper/server1/data");
    log.info("ZooKeeper dataDir:{}", zkDir);
    zkServer = new SaslZkTestServer(zkDir, createTempDir().resolve("miniKdc"));
    zkServer.run();

    System.setProperty("zkHost", zkServer.getZkAddress());

    try (SolrZkClient zkClient =
        new SolrZkClientWithACLs(zkServer.getZkHost(), AbstractZkTestCase.TIMEOUT)) {
      ZooKeeperSaslClient saslClient = zkClient.getZooKeeper().getSaslClient();
      assumeFalse("Could not set up ZK with SASL", saslClient.isFailed());
      zkClient.makePath("/solr", false, true);
    } catch (KeeperException e) {
      // This fails on Linux but passes on Windows and MacOS. Why?
      assumeNoException("Could not set up ZK chroot, see SOLR-15366.", e);
    }
    setupZNodes();

    if (log.isInfoEnabled()) {
      log.info("####SETUP_END {}", getTestName());
    }
  }

  protected void setupZNodes() throws Exception {
    SolrZkClient zkClient =
        new SolrZkClientWithACLs(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT);
    try {
      zkClient.create(
          "/protectedCreateNode", "content".getBytes(DATA_ENCODING), CreateMode.PERSISTENT, false);
      zkClient.makePath(
          "/protectedMakePathNode",
          "content".getBytes(DATA_ENCODING),
          CreateMode.PERSISTENT,
          false);
      zkClient.create(
          SecurityAwareZkACLProvider.SECURITY_ZNODE_PATH,
          "content".getBytes(DATA_ENCODING),
          CreateMode.PERSISTENT,
          false);
    } finally {
      zkClient.close();
    }

    zkClient = new SolrZkClientNoACLs(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT);
    try {
      zkClient.create(
          "/unprotectedCreateNode",
          "content".getBytes(DATA_ENCODING),
          CreateMode.PERSISTENT,
          false);
      zkClient.makePath(
          "/unprotectedMakePathNode",
          "content".getBytes(DATA_ENCODING),
          CreateMode.PERSISTENT,
          false);
    } finally {
      zkClient.close();
    }
  }

  @Override
  public void tearDown() throws Exception {
    System.clearProperty("zkHost");
    zkServer.shutdown();
    super.tearDown();
  }

  @Test
  public void testSaslZkACLProvider() throws Exception {
    // Test with Sasl enabled
    SolrZkClient zkClient =
        new SolrZkClientWithACLs(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT);
    try {
      AbstractDigestZkACLAndCredentialsProvidersTestBase.doTest(
          zkClient, true, true, true, true, true, true, true, true, true, true);
    } finally {
      zkClient.close();
    }

    // Test without Sasl enabled
    setupZNodes();
    System.setProperty("zookeeper.sasl.client", "false");
    zkClient = new SolrZkClientNoACLs(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT);
    try {
      AbstractDigestZkACLAndCredentialsProvidersTestBase.doTest(
          zkClient, true, true, false, false, false, false, false, false, false, false);
    } finally {
      zkClient.close();
      System.clearProperty("zookeeper.sasl.client");
    }
  }

  /** A SolrZKClient that adds Sasl ACLs */
  private static class SolrZkClientWithACLs extends SolrZkClient {

    public SolrZkClientWithACLs(String zkServerAddress, int zkClientTimeout) {
      super(
          new Builder()
              .withUrl(zkServerAddress)
              .withTimeout(zkClientTimeout, TimeUnit.MILLISECONDS));
    }

    @Override
    public ZkACLProvider createZkACLProvider() {
      return new SaslZkACLProvider();
    }
  }

  /** A SolrZKClient that doesn't add ACLs */
  private static class SolrZkClientNoACLs extends SolrZkClient {

    public SolrZkClientNoACLs(String zkServerAddress, int zkClientTimeout) {
      super(
          new Builder()
              .withUrl(zkServerAddress)
              .withTimeout(zkClientTimeout, TimeUnit.MILLISECONDS));
    }

    @Override
    public ZkACLProvider createZkACLProvider() {
      return new DefaultZkACLProvider();
    }
  }

  /** A ZkTestServer with Sasl support */
  public static class SaslZkTestServer extends ZkTestServer {
    private final Path kdcDir;
    private KerberosTestServices kerberosTestServices;

    public SaslZkTestServer(Path zkDir, Path kdcDir) throws Exception {
      super(zkDir);
      this.kdcDir = kdcDir;
    }

    @Override
    public void run() throws InterruptedException, IOException {
      try {
        // Don't require that credentials match the entire principal string, e.g.
        // can match "solr" rather than "solr/host@DOMAIN"
        System.setProperty("zookeeper.kerberos.removeRealmFromPrincipal", "true");
        System.setProperty("zookeeper.kerberos.removeHostFromPrincipal", "true");
        File keytabFile = kdcDir.resolve("keytabs").toFile();
        String zkClientPrincipal = "solr";
        String zkServerPrincipal = "zookeeper/localhost";

        kerberosTestServices =
            KerberosTestServices.builder()
                .withKdc(kdcDir.toFile())
                .withDebug() // SOLR-15366
                .withJaasConfiguration(zkClientPrincipal, keytabFile, zkServerPrincipal, keytabFile)
                .build();
        kerberosTestServices.start();

        kerberosTestServices
            .getKdc()
            .createPrincipal(keytabFile, zkClientPrincipal, zkServerPrincipal);
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
      super.run(false);
    }

    @Override
    public void shutdown() throws IOException, InterruptedException {
      System.clearProperty("zookeeper.authProvider.1");
      System.clearProperty("zookeeper.kerberos.removeRealmFromPrincipal");
      System.clearProperty("zookeeper.kerberos.removeHostFromPrincipal");
      super.shutdown();
      if (kerberosTestServices != null) kerberosTestServices.stop();
    }
  }
}

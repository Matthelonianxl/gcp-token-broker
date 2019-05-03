// Copyright 2019 Google LLC
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.broker.hadoop.fs;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.apache.hadoop.security.UserGroupInformation;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;


import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.internal.DnsNameResolverProvider;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.apache.hadoop.conf.Configuration;

// Classes dynamically generated by protobuf-maven-plugin:
import com.google.cloud.broker.protobuf.BrokerGrpc;


public final class BrokerGateway {

    private static final String SPNEGO_OID = "1.3.6.1.5.5.2";
    private static final String KRB5_MECHANISM_OID = "1.2.840.113554.1.2.2";
    private static final String KRB5_PRINCIPAL_NAME_OID = "1.2.840.113554.1.2.2.1";

    protected BrokerGrpc.BrokerBlockingStub stub;
    protected ManagedChannel managedChannel;
    protected Configuration config;

    // Timeout for RPC calls
    private static int DEADLINE_MILLISECONDS = 20*1000;

    public BrokerGateway(Configuration config, UserGroupInformation loginUser) throws GSSException {
        this(config, loginUser,null);
    }

    public BrokerGateway(Configuration config, UserGroupInformation loginUser, String sessionToken) throws GSSException {
        this.config = config;

        boolean tlsEnabled = config.getBoolean("gcp.token.broker.tls.enabled", true);
        String tlsCertificate = config.get("gcp.token.broker.tls.certificate", "");
        String brokerHostname = config.get("gcp.token.broker.uri.hostname");
        int brokerPort = config.getInt("gcp.token.broker.uri.port", 443);

        // Create the gRPC stub
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(brokerHostname, brokerPort)
            .nameResolverFactory(new DnsNameResolverProvider());
        if (!tlsEnabled) {
            builder = builder.usePlaintext();
        }
        if (!tlsCertificate.equals("")) {
            // A certificate is provided, so add it to the stub's build
            InputStream inputStream = new ByteArrayInputStream(tlsCertificate.getBytes());
            try {
                builder = builder.sslContext(GrpcSslContexts.forClient()
                    .trustManager(inputStream).build());
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }
        }
        managedChannel = builder
            .loadBalancerFactory(new PickFirstLoadBalancerProvider())
            .build();
        stub = BrokerGrpc.newBlockingStub(managedChannel)
            .withDeadlineAfter(DEADLINE_MILLISECONDS, TimeUnit.MILLISECONDS);

        if (sessionToken != null) {
            setDelegationToken(sessionToken);
        }
        else {
            String username = loginUser.getUserName();
            String realm = username.substring(username.indexOf("@") + 1);
            setSPNEGOToken(realm);
        }
    }

    public BrokerGrpc.BrokerBlockingStub getStub() {
        return stub;
    }

    public ManagedChannel getManagedChannel() {
        return managedChannel;
    }

    private void setSPNEGOToken(String realm) throws GSSException {
        String brokerHostname = config.get("gcp.token.broker.uri.hostname");
        String brokerServiceName = config.get("gcp.token.broker.servicename", "broker");

        // Create GSS context for the broker service and the logged-in user
        Oid krb5Mechanism = new Oid(KRB5_MECHANISM_OID);
        Oid krb5PrincipalNameType = new Oid(KRB5_PRINCIPAL_NAME_OID);
        Oid spnegoOid = new Oid(SPNEGO_OID);
        GSSManager manager = GSSManager.getInstance();
        String servicePrincipal = brokerServiceName + "/" + brokerHostname + "@" + realm;
        GSSName gssServerName = manager.createName(servicePrincipal , krb5PrincipalNameType, krb5Mechanism);
        GSSContext gssContext = manager.createContext(
            gssServerName, spnegoOid, null, GSSCredential.DEFAULT_LIFETIME);
        gssContext.requestMutualAuth(true);
        gssContext.requestCredDeleg(true);

        // Generate the authentication token
        byte[] authToken = new byte[0];
        authToken = gssContext.initSecContext(authToken, 0, authToken.length);
        String encodedToken = Base64.getEncoder().encodeToString(authToken);

        // Set the 'authorization' header
        Metadata metadata = new Metadata();
        Metadata.Key<String> key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(key, "Negotiate " + encodedToken);
        stub = MetadataUtils.attachHeaders(stub, metadata);
    }

    private void setDelegationToken(String sessionToken) {
        // Set the delegation token in the 'authorization' header
        Metadata metadata = new Metadata();
        Metadata.Key<String> key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(key, "BrokerSession " + sessionToken);
        stub = MetadataUtils.attachHeaders(stub, metadata);
    }

}
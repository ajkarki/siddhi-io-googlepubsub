/*
 *  Copyright (c) 2019  WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.extension.siddhi.io.googlepubsub.source;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PushConfig;
import org.apache.log4j.Logger;
import org.wso2.extension.siddhi.io.googlepubsub.util.GooglePubSubConstants;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.exception.ConnectionUnavailableException;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.stream.input.source.Source;
import org.wso2.siddhi.core.stream.input.source.SourceEventListener;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.OptionHolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * {@code GooglePubSubSource} Handle the GooglePubSub receiving tasks.
 */
@Extension(
        name = "googlepubsub",
        namespace = "source",
        description = "The GooglePubSub source receives events to be processed by Siddhi from a topic in a " +
                "GooglePubSub server. Here, a subscriber client creates a subscription to that topic and consumes" +
                " messages via the subscription. The subscription applications receive only the messages that are " +
                "published after the subscription is created. A subscription connects a topic to a subscriber" +
                " application, enabling the application to receive and process messages from that topic. A topic " +
                "can have multiple subscriptions, but a given subscription belongs only to a single topic.",
        parameters = {
                @Parameter(
                        name = GooglePubSubConstants.GOOGLE_PUB_SUB_SERVER_PROJECT_ID,
                        description = "The unique ID of the GCP console project within which the topic is created.",
                        type = DataType.STRING
                ),
                @Parameter(
                        name = GooglePubSubConstants.TOPIC_ID,
                        description = "The unique ID of the topic from which the messages are received.",
                        type = DataType.STRING
                ),
                @Parameter(
                        name = GooglePubSubConstants.SUBSCRIPTION_ID,
                        description = "The unique ID of the subscription from which messages must be retrieved.",
                        type = DataType.STRING
                ),
                @Parameter(
                        name = GooglePubSubConstants.CREDENTIAL_PATH,
                        description = "The file path of the service account credentials.",
                        type = DataType.STRING
                ),
        },
        examples = {
                @Example(
                        syntax = "@source(type='googlepubsub',@map(type='text'),\n"
                                + "topic.id='topicA',\n"
                                + "project.id='sp-path-1547649404768',\n"
                                + "credential.path = 'src/test/resources/security/sp.json',\n"
                                + "subscription.id='subA',\n"
                                + ")\n"
                                + "define stream OutputStream(message String);",
                        description = "This query shows how to subscribe to a googlepubsub topic. Here, a " +
                                "googlepubsub source subscribes to the 'topicA' topic that resides in the " +
                                "'sp-path-1547649404768' project within a googlepubsub instance. The events are " +
                                "received in the text format, mapped to a Siddhi event, and then sent to a stream " +
                                "named OutputStream."


                )
        }
)

public class GooglePubSubSource extends Source {

    private static final Logger log = Logger.getLogger(GooglePubSubSource.class);
    private String streamID;
    private String siddhiAppName;
    private SubscriptionAdminClient subscriptionAdminClient;
    private GooglePubSubMessageReceiver googlePubSubMessageReceiver;
    private Subscriber subscriber;
    private ProjectTopicName topicName;
    private ProjectSubscriptionName subscriptionName;
    private GoogleCredentials credentials;
    private String projectId;
    private String topicId;

    @Override
    public void init(SourceEventListener sourceEventListener, OptionHolder optionHolder,
                     String[] requestedTransportPropertyNames, ConfigReader configReader,
                     SiddhiAppContext siddhiAppContext) {

        this.streamID = sourceEventListener.getStreamDefinition().getId();
        this.siddhiAppName = siddhiAppContext.getName();
        String subscriptionId = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.SUBSCRIPTION_ID);
        this.topicId = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.TOPIC_ID);
        this.projectId = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.GOOGLE_PUB_SUB_SERVER_PROJECT_ID);
        String credentialPath = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.CREDENTIAL_PATH);
        googlePubSubMessageReceiver = new GooglePubSubMessageReceiver(sourceEventListener);
        this.topicName = ProjectTopicName.of(projectId, topicId);
        this.subscriptionName = ProjectSubscriptionName.of(projectId, subscriptionId);
        File credentialsPath = new File(credentialPath);
        try {
            FileInputStream serviceAccountStream = new FileInputStream(credentialsPath);
            credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
        } catch (IOException e) {
            throw new SiddhiAppCreationException("The file that contains your service account credentials is not"
                    + " found or you are not permitted to make authenticated calls.Check the credential.path '"
                    + credentialPath + "' defined in stream " + siddhiAppName + ": " + streamID, e);
        }
    }

    @Override
    public Class[] getOutputEventClasses() {

        return new Class[]{String.class};
    }

    @Override
    public void connect(ConnectionCallback connectionCallback)throws ConnectionUnavailableException {
        try {
            SubscriptionAdminSettings subscriptionAdminSettings = SubscriptionAdminSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build();
            subscriptionAdminClient = SubscriptionAdminClient.create(subscriptionAdminSettings);
            subscriptionAdminClient.createSubscription(subscriptionName, topicName, PushConfig.getDefaultInstance(),
                    10);
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() == StatusCode.Code.ALREADY_EXISTS) {
                log.info("You have a subscription " + subscriptionName + "to the topic " + topicName);
            } else {
                log.error("Error in connecting to the resources at " + siddhiAppName + ": " + streamID);
               throw new ConnectionUnavailableException("An error is caused due to resource " + e.getStatusCode()
                       .getCode() + "." + "Check whether you have provided a proper project.id for '" + projectId +
                       "' or " + "existing topic.id for '" + topicId + "' defined in stream " + siddhiAppName + ": " +
                       streamID + " and make sure you have enough access to use all resources in API.", e);
            }
        } catch (IOException e) {
            throw new ConnectionUnavailableException("Could not create a subscription " + subscriptionName + "to pull "
                    + "messages from the google pub sub server defined in stream " + siddhiAppName + ": " + streamID,
                    e);
        } finally {
            if (subscriptionAdminClient != null) {
                subscriptionAdminClient.shutdown();
            }
        }
        subscriber = Subscriber.newBuilder(subscriptionName, googlePubSubMessageReceiver).setCredentialsProvider
                (FixedCredentialsProvider.create(credentials)).build();
        subscriber.startAsync().awaitRunning();
    }

    @Override
    public void disconnect() {

        if (subscriber != null) {
            subscriber.stopAsync().awaitTerminated();
        }
    }

    @Override
    public void destroy() {
        // disconnect() gets called before destroy() which does the cleanup destroy() needs
    }

    @Override
    public void pause() {

        googlePubSubMessageReceiver.pause();
    }

    @Override
    public void resume() {

        googlePubSubMessageReceiver.resume();
    }

    @Override
    public Map<String, Object> currentState() {

        return null;
    }

    @Override
    public void restoreState(Map<String, Object> map) {
        // no state no restore
    }
}

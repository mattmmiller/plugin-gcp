package io.kestra.plugin.gcp.pubsub;

import com.google.api.core.ApiService;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.pubsub.v1.ProjectSubscriptionName;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gcp.pubsub.model.Message;
import io.kestra.plugin.gcp.pubsub.model.SerdeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow from message consumption in real-time from a Google Pub/Sub topic.",
    description = "If you would like to consume multiple messages processed within a given time frame and process them in batch, you can use the [io.kestra.plugin.gcp.pubsub.Trigger](https://kestra.io/plugins/plugin-gcp/triggers/io.kestra.plugin.gcp.pubsub.trigger) instead."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Consume a message from a Pub/Sub topic in real-time.",
            code = """
                id: realtime_pubsub
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Received: {{ trigger.data }}"

                triggers:
                  - id: trigger
                    type: io.kestra.plugin.gcp.pubsub.RealtimeTrigger
                    projectId: test-project-id
                    topic: test-topic
                    subscription: test-subscription
                """
        ),
        @Example(
            full = true,
            title = "Use GCP Pub/Sub Realtime Trigger to push events into Firestore",
            code = """
                id: pubsub_realtime_trigger
                namespace: company.team

                tasks:
                  - id: insert_into_firestore
                    type: io.kestra.plugin.gcp.firestore.Set
                    projectId: test-project-id
                    collection: orders
                    document:
                      order_id: "{{ trigger.data | jq('.order_id') | first }}"
                      customer_name: "{{ trigger.data | jq('.customer_name') | first }}"
                      customer_email: "{{ trigger.data | jq('.customer_email') | first }}"
                      product_id: "{{ trigger.data | jq('.product_id') | first }}"
                      price: "{{ trigger.data | jq('.price') | first }}"
                      quantity: "{{ trigger.data | jq('.quantity') | first }}"
                      total: "{{ trigger.data | jq('.total') | first }}"

                triggers:
                  - id: realtime_trigger
                    type: io.kestra.plugin.gcp.pubsub.RealtimeTrigger
                    projectId: test-project-id
                    topic: orders
                    subscription: kestra-subscription
                    serdeType: JSON
                """
        )
    }
)
public class RealtimeTrigger extends AbstractTrigger implements RealtimeTriggerInterface, TriggerOutput<Message>, PubSubConnectionInterface {

    private Property<String> projectId;

    private Property<String> serviceAccount;

    private Property<String> impersonatedServiceAccount;

    @Builder.Default
    private Property<List<String>> scopes = Property.ofValue(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));

    private Property<String> topic;

    @Schema(
        title = "The Pub/Sub subscription",
        description = "The Pub/Sub subscription. It will be created automatically if it didn't exist and 'autoCreateSubscription' is enabled."
    )
    private Property<String> subscription;

    @Schema(
        title = "Whether the Pub/Sub subscription should be created if not exist"
    )
    @Builder.Default
    private Property<Boolean> autoCreateSubscription = Property.ofValue(true);

    @Builder.Default
    private final Property<Duration> interval = Property.ofValue(Duration.ofSeconds(60));

    @Schema(title = "Max number of records, when reached the task will end.")
    private Property<Integer> maxRecords;

    @Schema(title = "Max duration in the Duration ISO format, after that the task will end.")
    private Property<Duration> maxDuration;

    @Builder.Default
    @NotNull
    @Schema(title = "The serializer/deserializer to use.")
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final CountDownLatch waitForTermination = new CountDownLatch(1);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicReference<Subscriber> subscriberReference = new AtomicReference<>();

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        Consume task = Consume.builder()
            .topic(this.topic)
            .subscription(this.subscription)
            .autoCreateSubscription(this.autoCreateSubscription)
            .projectId(this.projectId)
            .serviceAccount(this.serviceAccount)
            .scopes(this.scopes)
            .maxRecords(this.maxRecords)
            .maxDuration(this.maxDuration)
            .serdeType(this.serdeType)
            .build();

        return Flux.from(publisher(task, conditionContext.getRunContext()))
            .map(message -> TriggerService.generateRealtimeExecution(this, conditionContext, context, message));
    }

    private Publisher<Message> publisher(final Consume task, final RunContext runContext) throws Exception {
        ProjectSubscriptionName subscriptionName = task.createSubscription(
            runContext,
            runContext.render(subscription).as(String.class).orElse(null),
            runContext.render(autoCreateSubscription).as(Boolean.class).orElse(true)
        );
        GoogleCredentials credentials = task.credentials(runContext);

        var serdeTypeRendered = runContext.render(serdeType).as(SerdeType.class).orElseThrow();
        return Flux.create(
            emitter -> {
                AtomicInteger total = new AtomicInteger();
                final MessageReceiver receiver = (message, consumer) -> {
                    try {
                        emitter.next(Message.of(message, serdeTypeRendered));
                        total.getAndIncrement();
                        consumer.ack();
                    }  catch(Exception exception) {
                        emitter.error(exception);
                        consumer.nack();
                    }
                };

                Subscriber subscriber = Subscriber
                    .newBuilder(subscriptionName, receiver)
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();

                this.subscriberReference.set(subscriber);

                try {
                    subscriber.addListener(
                        new ApiService.Listener() {
                            @Override
                            public void failed(ApiService.State from, Throwable failure) {
                                emitter.error(failure);
                                waitForTermination.countDown();
                            }

                            @Override
                            public void terminated(ApiService.State from) {
                                emitter.complete();
                                waitForTermination.countDown();
                            }
                        }, MoreExecutors.directExecutor()
                    );
                    subscriber.startAsync().awaitRunning();
                } catch (Exception exception) {
                    if (subscriber.isRunning()) {
                        subscriber.stopAsync().awaitTerminated();
                    }
                    emitter.error(exception);
                    waitForTermination.countDown();
                }
            });
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public void kill() {
        stop(true);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public void stop() {
        stop(false); // must be non-blocking
    }

    private void stop(boolean wait) {
        if (!isActive.compareAndSet(true, false)) {
            return;
        }

        Optional.ofNullable(subscriberReference.get()).ifPresent(subscriber -> {
            subscriber.stopAsync(); // Shut down the PubSub subscriber.
            if (wait) {
                try {
                    this.waitForTermination.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}

package io.kestra.plugin.gcp.firestore;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.annotation.Value;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class SetTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Value("${kestra.tasks.firestore.project}")
    private String project;

    @Test
    void runMap() throws Exception {
        var runContext = runContextFactory.of();

        var set = Set.builder()
            .projectId(Property.ofValue(project))
            .collection(Property.ofValue("persons"))
            .childPath(Property.ofValue("1"))
            .document(Map.of("firstname", "John",
                "lastname", "Doe"
            ))
            .build();

        var output = set.run(runContext);

        assertThat(output.getUpdatedTime(), is(notNullValue()));

        // clear the collection
        try (var firestore = set.connection(runContext)) {
            FirestoreTestUtil.clearCollection(firestore, "persons");
        }
    }

    @Test
    void runString() throws Exception {
        var runContext = runContextFactory.of();

        var set = Set.builder()
            .projectId(Property.ofValue(project))
            .collection(Property.ofValue("persons"))
            .childPath(Property.ofValue("2"))
            .document("{\"firstname\":\"Jane\",\"lastname\":\"Doe\"}")
            .build();

        var output = set.run(runContext);

        assertThat(output.getUpdatedTime(), is(notNullValue()));

        // clear the collection
        try (var firestore = set.connection(runContext)) {
            FirestoreTestUtil.clearCollection(firestore, "persons");
        }
    }

    @Test
    void runNull() throws Exception {
        var runContext = runContextFactory.of();

        var set = Set.builder()
            .projectId(Property.ofValue(project))
            .collection(Property.ofValue("persons"))
            .childPath(Property.ofValue("3"))
            .document(null)
            .build();

        var output = set.run(runContext);

        assertThat(output.getUpdatedTime(), is(notNullValue()));

        // clear the collection
        try (var firestore = set.connection(runContext)) {
            FirestoreTestUtil.clearCollection(firestore, "persons");
        }
    }
}

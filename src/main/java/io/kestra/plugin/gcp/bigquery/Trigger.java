package io.kestra.plugin.gcp.bigquery;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow if a periodically executed BigQuery query returns a non-empty result set."
)
@Plugin(
    examples = {
        @Example(
            title = "Wait for a sql query to return results and iterate through rows.",
            full = true,
            code = """
                id: bigquery_listen
                namespace: company.team

                tasks:
                  - id: each
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ trigger.rows }}"
                    tasks:
                      - id: return
                        type: io.kestra.plugin.core.debug.Return
                        format: "{{ taskrun.value }}"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.gcp.bigquery.Trigger
                    interval: "PT5M"
                    sql: "SELECT * FROM `myproject.mydataset.mytable`"
                    fetch: true
                """
        )
    }
)
@StoreFetchValidation
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Query.Output>, QueryInterface {
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    protected Property<String> projectId;
    protected Property<String> serviceAccount;
    @Builder.Default
    protected Property<java.util.List<String>> scopes = Property.ofValue(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));

    private Property<String> sql;

    @Builder.Default
    private Property<Boolean> legacySql = Property.ofValue(false);

    @Builder.Default
    @Deprecated
    private boolean fetch = false;

    @Builder.Default
    @Deprecated
    private boolean store = false;

    @Builder.Default
    @Deprecated
    private boolean fetchOne = false;

    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.NONE);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        Query task = Query.builder()
            .id(this.id)
            .type(Query.class.getName())
            .projectId(this.projectId)
            .serviceAccount(this.serviceAccount)
            .scopes(this.scopes)
            .sql(this.sql)
            .legacySql(this.legacySql)
            .fetch(this.fetch)
            .store(this.store)
            .fetchType(this.fetchType)
            .fetchOne(this.fetchOne)
            .build();
        Query.Output run = task.run(runContext);

        logger.debug("Found '{}' rows from '{}'", run.getSize(), runContext.render(this.sql));

        if (run.getSize() == 0) {
            return Optional.empty();
        }

        Execution execution = TriggerService.generateExecution(this, conditionContext, context, run);

        return Optional.of(execution);
    }
}

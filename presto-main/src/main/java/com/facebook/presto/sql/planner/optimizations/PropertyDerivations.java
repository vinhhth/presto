/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.TableLayout;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.GroupingProperty;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.SortingProperty;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.DomainTranslator;
import com.facebook.presto.sql.planner.ExpressionInterpreter;
import com.facebook.presto.sql.planner.NoOpSymbolResolver;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.DistinctLimitNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.IndexJoinNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanVisitor;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.RowNumberNode;
import com.facebook.presto.sql.planner.plan.SampleNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableCommitNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.TopNRowNumberNode;
import com.facebook.presto.sql.planner.plan.UnnestNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.sql.analyzer.ExpressionAnalyzer.getExpressionTypes;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.google.common.base.Preconditions.checkArgument;

class PropertyDerivations
{
    private PropertyDerivations() {}

    public static ActualProperties deriveProperties(PlanNode node, ActualProperties inputProperties, Metadata metadata, Session session, Map<Symbol, Type> types, SqlParser parser)
    {
        return deriveProperties(node, ImmutableList.of(inputProperties), metadata, session, types, parser);
    }

    public static ActualProperties deriveProperties(PlanNode node, List<ActualProperties> inputProperties, Metadata metadata, Session session, Map<Symbol, Type> types, SqlParser parser)
    {
        return node.accept(new Visitor(metadata, session, types, parser), inputProperties);
    }

    private static class Visitor
            extends PlanVisitor<List<ActualProperties>, ActualProperties>
    {
        private final Metadata metadata;
        private final Session session;
        private final Map<Symbol, Type> types;
        private final SqlParser parser;

        public Visitor(Metadata metadata, Session session, Map<Symbol, Type> types, SqlParser parser)
        {
            this.metadata = metadata;
            this.session = session;
            this.types = types;
            this.parser = parser;
        }

        @Override
        protected ActualProperties visitPlan(PlanNode node, List<ActualProperties> inputProperties)
        {
            throw new UnsupportedOperationException("not yet implemented: " + node.getClass().getName());
        }

        @Override
        public ActualProperties visitOutput(OutputNode node, List<ActualProperties> inputProperties)
        {
            return Iterables.getOnlyElement(inputProperties);
        }

        @Override
        public ActualProperties visitMarkDistinct(MarkDistinctNode node, List<ActualProperties> inputProperties)
        {
            return Iterables.getOnlyElement(inputProperties);
        }

        @Override
        public ActualProperties visitWindow(WindowNode node, List<ActualProperties> inputProperties)
        {
            ImmutableList.Builder<LocalProperty<Symbol>> localProperties = ImmutableList.builder();
            localProperties.add(new GroupingProperty<>(node.getPartitionBy()));
            for (Symbol column : node.getOrderBy()) {
                localProperties.add(new SortingProperty<>(column, node.getOrderings().get(column)));
            }

            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(localProperties.build())
                    .constants(properties)
                    .build();
        }

        @Override
        public ActualProperties visitAggregation(AggregationNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            if (properties.isUnpartitioned()) {
                return ActualProperties.builder()
                        .unpartitioned()
                        .coordinatorOnly(properties)
                        .local(LocalProperties.grouped(node.getGroupBy()))
                        .constants(Maps.filterKeys(properties.getConstants(), ImmutableSet.of(node.getGroupBy())::contains))
                        .build();
            }

            return ActualProperties.builder()
                    .partitioned(properties)
                    .local(LocalProperties.grouped(node.getGroupBy()))
                    .constants(Maps.filterKeys(properties.getConstants(), ImmutableSet.of(node.getGroupBy())::contains))
                    .build();
        }

        @Override
        public ActualProperties visitRowNumber(RowNumberNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(LocalProperties.grouped(node.getPartitionBy()))
                    .constants(properties)
                    .build();
        }

        @Override
        public ActualProperties visitTopNRowNumber(TopNRowNumberNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            ImmutableList.Builder<LocalProperty<Symbol>> localProperties = ImmutableList.builder();
            localProperties.add(new GroupingProperty<>(node.getPartitionBy()));
            for (Symbol column : node.getOrderBy()) {
                localProperties.add(new SortingProperty<>(column, node.getOrderings().get(column)));
            }

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(localProperties.build())
                    .constants(properties)
                    .build();
        }

        @Override
        public ActualProperties visitTopN(TopNNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            List<SortingProperty<Symbol>> localProperties = node.getOrderBy().stream()
                    .map(column -> new SortingProperty<>(column, node.getOrderings().get(column)))
                    .collect(toImmutableList());

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(localProperties)
                    .constants(properties)
                    .build();
        }

        @Override
        public ActualProperties visitSort(SortNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            List<SortingProperty<Symbol>> localProperties = node.getOrderBy().stream()
                    .map(column -> new SortingProperty<>(column, node.getOrderings().get(column)))
                    .collect(toImmutableList());

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(localProperties)
                    .constants(properties)
                    .build();
        }

        @Override
        public ActualProperties visitLimit(LimitNode node, List<ActualProperties> inputProperties)
        {
            return Iterables.getOnlyElement(inputProperties);
        }

        @Override
        public ActualProperties visitDistinctLimit(DistinctLimitNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(LocalProperties.grouped(node.getDistinctSymbols()))
                    .constants(properties)
                    .build();
        }

        @Override
        public ActualProperties visitTableCommit(TableCommitNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            return ActualProperties.builder()
                    .unpartitioned()
                    .coordinatorOnly(properties)
                    .build();
        }

        @Override
        public ActualProperties visitJoin(JoinNode node, List<ActualProperties> inputProperties)
        {
            // TODO: include all equivalent columns in partitioning properties
            // TODO: derive constants for right side
            return inputProperties.get(0);
        }

        @Override
        public ActualProperties visitSemiJoin(SemiJoinNode node, List<ActualProperties> inputProperties)
        {
            return inputProperties.get(0);
        }

        @Override
        public ActualProperties visitIndexJoin(IndexJoinNode node, List<ActualProperties> inputProperties)
        {
            return inputProperties.get(0);
        }

        @Override
        public ActualProperties visitExchange(ExchangeNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = inputProperties.get(0);

            switch (node.getType()) {
                case GATHER:
                    return ActualProperties.builder()
                            .unpartitioned()
                            .constants(properties)
                            .build();
                case REPARTITION:
                    return ActualProperties.builder()
                            .hashPartitioned(node.getPartitionKeys())
                            .constants(properties)
                            .build();
                case REPLICATE:
                    return ActualProperties.builder()
                            .partitioned(properties)
                            .constants(properties)
                            .build();
            }

            throw new UnsupportedOperationException("not yet implemented");
        }

        @Override
        public ActualProperties visitFilter(FilterNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            DomainTranslator.ExtractionResult decomposedPredicate = DomainTranslator.fromPredicate(
                    metadata,
                    session,
                    node.getPredicate(),
                    types);

            Map<Symbol, Object> constants = new HashMap<>(properties.getConstants());
            constants.putAll(decomposedPredicate.getTupleDomain().extractFixedValues());

            return ActualProperties.builder()
                    .partitioned(properties)
                    .coordinatorOnly(properties)
                    .local(properties)
                    .constants(constants)
                    .build();
        }

        @Override
        public ActualProperties visitProject(ProjectNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            Map<Symbol, Symbol> identities = computeIdentityTranslations(node.getAssignments());

            ImmutableList.Builder<LocalProperty<Symbol>> localProperties = ImmutableList.<LocalProperty<Symbol>>builder();
            for (LocalProperty<Symbol> property : properties.getLocalProperties()) {
                Optional<LocalProperty<Symbol>> translated = property.translate(column -> Optional.ofNullable(identities.get(column)));
                if (!translated.isPresent()) {
                    break;
                }
                localProperties.add(translated.get());
            }

            Map<Symbol, Object> constants = new HashMap<>();
            for (Map.Entry<Symbol, Expression> assignment : node.getAssignments().entrySet()) {
                Expression expression = assignment.getValue();

                IdentityHashMap<Expression, Type> expressionTypes = getExpressionTypes(session, metadata, parser, types, expression);
                ExpressionInterpreter optimizer = ExpressionInterpreter.expressionOptimizer(expression, metadata, session, expressionTypes);
                // TODO:
                // We want to use a symbol resolver that looks up in the constants from the input subplan
                // to take advantage of constant-folding for complex expressions
                // However, that currently causes errors when those expressions operate on arrays or row types
                // ("ROW comparison not supported for fields with null elements", etc)
                Object value = optimizer.optimize(NoOpSymbolResolver.INSTANCE);

                if (value instanceof QualifiedNameReference) {
                    value = constants.get(Symbol.fromQualifiedName(((QualifiedNameReference) value).getName()));
                }

                if (!(value instanceof Expression)) {
                    constants.put(assignment.getKey(), value);
                }
            }

            if (properties.isHashPartitioned()) {
                Optional<List<Symbol>> translated = translate(properties.getHashPartitioningColumns().get(), identities);

                if (translated.isPresent()) {
                    return ActualProperties.builder()
                            .coordinatorOnly(properties)
                            .hashPartitioned(translated.get())
                            .local(localProperties.build())
                            .constants(constants)
                            .build();
                }
            }

            if (properties.hasKnownPartitioningScheme()) {
                Optional<List<Symbol>> translated = translate(properties.getPartitioningColumns().get(), identities);

                if (translated.isPresent()) {
                    return ActualProperties.builder()
                            .coordinatorOnly(properties)
                            .partitioned(ImmutableSet.copyOf(translated.get()))
                            .local(localProperties.build())
                            .constants(constants)
                            .build();
                }
            }

            return ActualProperties.builder()
                    .coordinatorOnly(properties)
                    .partitioned()
                    .local(localProperties.build())
                    .constants(constants)
                    .build();
        }

        @Override
        public ActualProperties visitTableWriter(TableWriterNode node, List<ActualProperties> inputProperties)
        {
            ActualProperties properties = Iterables.getOnlyElement(inputProperties);

            ActualProperties.Builder derived = ActualProperties.builder()
                    .coordinatorOnly(properties);

            if (properties.isUnpartitioned()) {
                derived.unpartitioned();
            }
            else {
                derived.partitioned();
            }

            return derived.build();
        }

        @Override
        public ActualProperties visitSample(SampleNode node, List<ActualProperties> inputProperties)
        {
            return Iterables.getOnlyElement(inputProperties);
        }

        @Override
        public ActualProperties visitUnnest(UnnestNode node, List<ActualProperties> inputProperties)
        {
            return Iterables.getOnlyElement(inputProperties);
        }

        @Override
        public ActualProperties visitTableScan(TableScanNode node, List<ActualProperties> inputProperties)
        {
            checkArgument(node.getLayout().isPresent(), "table layout has not yet been chosen");

            TableLayout layout = metadata.getLayout(node.getLayout().get());
            Map<ColumnHandle, Symbol> assignments = ImmutableBiMap.copyOf(node.getAssignments()).inverse();

            // partitioning properties
            Optional<List<Symbol>> partitioningColumns = Optional.empty();
            if (layout.getPartitioningColumns().isPresent()) {
                partitioningColumns = translate(layout.getPartitioningColumns().get(), assignments);
            }

            ActualProperties.Builder properties = ActualProperties.builder();
            if (partitioningColumns.isPresent()) {
                properties.partitioned(ImmutableSet.copyOf(partitioningColumns.get()));
            }
            else {
                properties.partitioned();
            }

            // local properties
            ImmutableList.Builder<LocalProperty<Symbol>> localProperties = ImmutableList.<LocalProperty<Symbol>>builder();
            for (LocalProperty<ColumnHandle> property : layout.getLocalProperties()) {
                Optional<LocalProperty<Symbol>> translated = property.translate(column -> Optional.ofNullable(assignments.get(column)));
                if (!translated.isPresent()) {
                    break;
                }
                localProperties.add(translated.get());
            }

            properties.local(localProperties.build());

            // constant assignments
            Map<Symbol, Object> constants = new HashMap<>();
            Map<ColumnHandle, Comparable<?>> fixedValues = node.getCurrentConstraint().extractFixedValues();
            for (Map.Entry<ColumnHandle, Symbol> entry : assignments.entrySet()) {
                if (fixedValues.containsKey(entry.getKey())) {
                    constants.put(entry.getValue(), fixedValues.get(entry.getKey()));
                }
            }

            properties.constants(constants);

            return properties.build();
        }

        private static Map<Symbol, Symbol> computeIdentityTranslations(Map<Symbol, Expression> assignments)
        {
            Map<Symbol, Symbol> inputToOutput = new HashMap<>();
            for (Map.Entry<Symbol, Expression> assignment : assignments.entrySet()) {
                if (assignment.getValue() instanceof QualifiedNameReference) {
                    inputToOutput.put(Symbol.fromQualifiedName(((QualifiedNameReference) assignment.getValue()).getName()), assignment.getKey());
                }
            }
            return inputToOutput;
        }

        /**
         * @return Optional.empty() if not all columns could be translated
         */
        private static <T> Optional<List<Symbol>> translate(Collection<T> columns, Map<T, Symbol> mappings)
        {
            ImmutableList.Builder<Symbol> builder = ImmutableList.builder();

            for (T column : columns) {
                Symbol translated = mappings.get(column);
                if (translated == null) {
                    return Optional.empty();
                }
                builder.add(translated);
            }

            return Optional.of(builder.build());
        }
    }
}
